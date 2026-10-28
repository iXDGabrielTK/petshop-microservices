# ADR-0004: Transactional Outbox Pattern com PostgreSQL `FOR UPDATE SKIP LOCKED`

* **Status:** Aceito
* **Data:** 2026-08-28
* **Autores:** Equipe de Engenharia
* **Bounded Contexts Afetados:** Shared, Checkout, Inventory
* **Serviços:** `apps/inv-service`

---

## 1. Contexto e Problema (Context & Problem Statement)

Quando uma venda é concluída ou um estoque atinge o limite mínimo, o sistema precisa notificar outros serviços (como o `mail-service` para envio de e-mails transacionais ou outros consumidores AMQP).

O problema clássico ao integrar Banco de Dados Relacional e Message Broker é o **Dual-Write Problem**:
1. **Salvar no banco primeiro e depois postar no RabbitMQ:** Se a aplicação sofrer um crash ou a rede cair logo após o commit do banco, o evento nunca será publicado no RabbitMQ (**perda de evento**).
2. **Publicar no RabbitMQ primeiro e depois salvar no banco:** Se o banco rejeitar a transação por erro de constraint ou conflito de lock, a mensagem já foi enviada e causará efeitos colaterais indevidos nos consumidores (**evento fantasma**).
3. **Transações Distribuídas 2PC (Two-Phase Commit / XA):** Extremamente complexas, pesadas, geram alto acoplamento temporal e não são suportadas nativamente pelo RabbitMQ.

---

## 2. Drivers de Decisão (Decision Drivers / Forces)

* **Atomicidade Garantida (Zero Event Loss):** Um evento só deve ser despachado se, e somente se, a transação de negócio correspondente for comitada no banco de dados.
* **Escalabilidade Horizontal de Workers:** Capacidade de rodar múltiplas instâncias do `inv-service` em paralelo consumindo a mesma tabela de outbox sem colisão de locks ou deadlocks.
* **Desacoplamento de Disponibilidade do Broker:** A venda no PDV deve ser concluída mesmo se o RabbitMQ estiver temporariamente fora do ar.
* **Ordenação Causal por Criação:** Despacho ordenado cronologicamente (`ORDER BY created_at ASC`).

---

## 3. Opções Consideradas (Considered Options)

1. **Opção 1: Publicação Direta com `@TransactionalEventListener(AFTER_COMMIT)`:**
   * *Problema:* Se o RabbitMQ estiver instável no instante do envio, a mensagem é perdida para sempre pois não há persistência de reenvio.
2. **Opção 2: Change Data Capture (CDC) com Debezium + Kafka Connect:**
   * *Problema:* Complexidade operacional excessiva para o estágio atual da infraestrutura.
3. **Opção 3: Transactional Outbox com Polling via `FOR UPDATE SKIP LOCKED` (Adotada):**
   * A entidade de negócio e o registro do evento na tabela `outbox` são gravados na **mesma transação ACID local**.
   * Um worker agendado consome a tabela `outbox` usando `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1`.
   * Envia a mensagem ao broker e remove o registro da tabela. Se o envio falhar, o rollback mantém o registro para a próxima tentativa.

---

## 4. Decisão Tomada e Justificativa (Decision Outcome)

Decidimos adotar a **Opção 3: Transactional Outbox com `SKIP LOCKED`**.

### A. Tabela Outbox no Schema ([`V1__Schema_Inicial.sql`](../../apps/inv-service/src/main/resources/db/migration/V1__Schema_Inicial.sql#L47-L56))
```sql
CREATE TABLE outbox (
    id BIGSERIAL PRIMARY KEY,
    exchange VARCHAR(255) NOT NULL,
    routing_key VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL
);
```

### B. Busca Sem Bloqueio Concorrente ([`OutboxRepository.java`](../../apps/inv-service/src/main/java/inv/shared/outbox/repository/OutboxRepository.java#L13-L20))
```sql
SELECT * FROM outbox 
ORDER BY created_at ASC 
LIMIT 1 
FOR UPDATE SKIP LOCKED;
```
* **O que faz o `SKIP LOCKED`:** Se a Instância A travar o registro de ID 10 para processamento, a Instância B que executar a query no mesmo milissegundo irá **pular** o ID 10 automaticamente e travar o ID 11, sem esperar e sem gerar contenção.

### C. Processador Transacional Isolado ([`OutboxProcessor.java`](../../apps/inv-service/src/main/java/inv/shared/outbox/scheduler/OutboxProcessor.java#L32-L67))
* Executa em transação própria com `Propagation.REQUIRES_NEW`.
* Deserializa o payload baseado em `eventType`.
* Envia via `RabbitTemplate.convertAndSend()`.
* Executa `outboxRepository.delete(message)` e comita a transação.
* Em caso de falha de rede com o RabbitMQ, a transação sofre rollback e o evento permanece salvo para novas tentativas.

---

## 5. Prós e Contras das Opções Analisadas

| Padrão | Garantia de Entrega | Suporte Multi-Instância | Sobrecarga de Infra | Complexidade |
| :--- | :--- | :--- | :--- | :--- |
| **Envio Síncrono Direto** | Fraca (Risco de perda) | N/A | Zero | Baixíssima |
| **CDC (Debezium + Kafka)** | Altíssima | Altíssima | Altíssima (Kafka Connect) | Muito Alta |
| **Outbox com SKIP LOCKED** | **Altíssima (Pelo menos 1x)** | **Nativa (Zero locks)** | **Mínima (Apenas Postgres)** | **Média** |

---

## 6. Estratégia de Validação & Testes de Carga/Concorrência (Verification & Testing)

> [!IMPORTANT]
> Este teste está implementado na suíte de testes de integração e é executado contra contêineres reais do PostgreSQL 15 e RabbitMQ 3 via Testcontainers.
> Classe de teste oficial (CI): [`OutboxConcorrenciaIntegrationTest`](../../apps/inv-service/src/test/java/inv/shared/outbox/OutboxConcorrenciaIntegrationTest.java)
> Runner manual local (dev): [`OutboxConcurrencyManualRunner`](../../apps/inv-service/src/test/java/inv/scheduler/OutboxConcurrencyManualRunner.java)
> Comando de execução:
> ```bash
> mvn test -pl apps/inv-service -Dtest=OutboxConcorrenciaIntegrationTest
> ```

### Cenário de Teste Automatizado: *Stress Test de Workers Paralelos de Outbox*
* **Classe:** `inv.shared.outbox.OutboxConcorrenciaIntegrationTest`
* **Configuração:** Testcontainers com PostgreSQL 15 (`postgres:15-alpine`) e RabbitMQ 3 (`rabbitmq:3-management`).
* **Massa de Teste:**
  * Inserção prévia de **100 mensagens** na tabela `outbox`.
  * Simulação de **10 instâncias/threads simultâneas de workers** executando `OutboxProcessor.processNext()` em loop contínuo até que a fila esteja vazia.
* **Mecanismo de Sincronismo:**
  * `ExecutorService` com thread pool de 10 threads.
  * Start gate via `CountDownLatch(1)` e end gate via `CountDownLatch(10)`.
  * Fila temporária de teste dedicada no RabbitMQ para validação de entrega real sem mock.

### Critérios de Aceite Obrigatórios (Asserts Validados)
1. **Contagem de Mensagens Publicadas:** Exatamente **100 mensagens** chegam à fila do RabbitMQ.
2. **Zero Duplicidades de Despacho:** Nenhuma mensagem com o mesmo payload/id de outbox é postada mais de uma vez (100 IDs distintos coletados).
3. **Drenagem Completa da Tabela:** A tabela `outbox` termina com **exatamente 0 linhas**.
4. **Ausência de Conflitos Transacionais:** Zero ocorrências de `PSQLException: deadlock detected` ou `CannotAcquireLockException`.

---

## 7. Referências e Links Relevantes

* [OutboxProcessor](../../apps/inv-service/src/main/java/inv/shared/outbox/scheduler/OutboxProcessor.java)
* [OutboxRepository](../../apps/inv-service/src/main/java/inv/shared/outbox/repository/OutboxRepository.java)
* [OutboxScheduler](../../apps/inv-service/src/main/java/inv/shared/outbox/scheduler/OutboxScheduler.java)
* [Microservices.io - Transactional Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html)
