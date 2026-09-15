# ADR-0006: Projeções CQRS Assíncronas (`AFTER_COMMIT`) com Auto-Cura via Tabela de Retry e `SKIP LOCKED`

* **Status:** Aceito
* **Data:** 2026-08-28
* **Autores:** Equipe de Engenharia
* **Bounded Contexts Afetados:** Finance, Checkout
* **Serviços:** `apps/inv-service`

---

## 1. Contexto e Problema (Context & Problem Statement)

Com a adoção do **Ledger Append-Only** ([ADR-0005](0005-append-only-financial-ledger-and-idempotency.md)), cada venda e pagamento gera múltiplos lançamentos individuais. 

Consultar o saldo consolidado de uma venda em tempo real para dashboards e relatórios executando `SUM(valor)` sobre a tabela de lançamentos gera alto consumo de CPU e I/O à medida que o volume cresce.

Por outro lado, atualizar a tabela de saldo de forma **síncrona** dentro da transação principal de checkout:
1. Aumenta o tempo de resposta da API do PDV.
2. Gera contenção de locks cruzados entre a escrita do pedido e a leitura analítica.
3. Se a projeção falhar, causaria o rollback indevido de uma venda que já teve o pagamento recebido.

Se a projeção for feita de forma **assíncrona pura**, qualquer crash da aplicação ou timeout de banco durante a projeção geraria uma **inconsistência silenciosa e permanente** (o dinheiro entrou no ledger, mas nunca apareceu na projeção).

---

## 2. Drivers de Decisão (Decision Drivers / Forces)

* **Baixa Latência no Checkout:** A gravação da venda e pagamento deve responder imediatamente após o commit do ledger.
* **Leituras O(1) de Saldo:** Dashboards e APIs de consulta devem ler o saldo já agregado sem calcular somatórios em tempo de execução.
* **Consistência Eventual Garantida (Self-Healing / Auto-Cura):** Nenhuma falha temporária ou restart de container pode deixar a projeção desatualizada em relação ao ledger.
* **Idempotência no Processamento Assíncrono:** Garantir processamento *exactly-once* lógico mesmo em cenários de retries múltiplos.

---

## 3. Opções Consideradas (Considered Options)

1. **Opção 1: Projeção Síncrona na Mesma Transação:** Gravar no `lancamentos_financeiros` e fazer o `UPDATE financial_projection_venda` no mesmo bloco `@Transactional`.
   * *Problema:* Aumenta tempo de retenção de locks e tempo de resposta do endpoint.
2. **Opção 2: Fila Externa (RabbitMQ) para Projeções:** Publicar mensagem no RabbitMQ e consumir em outro listener.
   * *Problema:* Overhead de broker e serialização para uma operação que ocorre dentro do mesmo Bounded Context e banco de dados.
3. **Opção 3: CQRS Assíncrono In-Memory (`AFTER_COMMIT`) com Checkpoint + Retry Queue com `SKIP LOCKED`:**
   * O listener dispara apenas após o commit com sucesso da transação principal (`TransactionPhase.AFTER_COMMIT`).
   * Roda em pool assíncrona dedicada (`@Async("projectionExecutor")`).
   * Utiliza tabela de checkpoint (`financial_projection_checkpoint`) para deduplicação.
   * Em caso de falha, persiste o payload na `projection_retry_queue` para reprocessamento por scheduler distribuído com `FOR UPDATE SKIP LOCKED`.

---

## 4. Decisão Tomada e Justificativa (Decision Outcome)

Decidimos adotar a **Opção 3: Pipeline CQRS Assíncrono com Auto-Cura**.

### A. Gatilho Transacional e Deduplicação ([`FinancialProjectionListener.java`](../../apps/inv-service/src/main/java/inv/finance/infrastructure/messaging/FinancialProjectionListener.java#L43-L65))
```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Async("projectionExecutor")
public void onLancamentoRegistrado(LancamentoRegistradoEvent event) {
    processarComIdempotencia(event);
}
```
* **Checkpoint Atômico:** O listener tenta inserir o `lancamento_id` na tabela `financial_projection_checkpoint`. Se já existir (retry anterior), uma `DuplicateKeyException` é capturada e a execução é finalizada de forma idempotente.
* **Upsert Atômico:** A projeção em `financial_projection_venda` é atualizada via query nativa com `ON CONFLICT (venda_id) DO UPDATE`.

### B. Auto-Cura de Falhas com `SKIP LOCKED` ([`ProjectionRetryScheduler.java`](../../apps/inv-service/src/main/java/inv/finance/infrastructure/jobs/ProjectionRetryScheduler.java) e [`ProjectionRetryRepository.java`](../../apps/inv-service/src/main/java/inv/finance/infrastructure/persistence/ProjectionRetryRepository.java#L11-L20))
Se a projeção assíncrona falhar por timeout ou indisponibilidade:
1. O evento é salvo na tabela `projection_retry_queue` com o payload JSON, erro e `status = 'PENDENTE'`.
2. Um scheduler executado a cada 60 segundos busca lotes de até 100 itens usando:
   ```sql
   SELECT * FROM projection_retry_queue
   WHERE proxima_execucao <= now() AND tentativas < 5 AND status = 'PENDENTE'
   FOR UPDATE SKIP LOCKED
   LIMIT 100;
   ```
3. Múltiplas instâncias do serviço podem rodar o scheduler simultaneamente sem colisão de locks. Após reprocessar com sucesso, o registro é removido da fila.

---

## 5. Prós e Contras das Opções Analisadas

| Característica | Projeção Síncrona | Fila Externa RabbitMQ | CQRS In-Memory + Retry Queue (Adotada) |
| :--- | :--- | :--- | :--- |
| **Latência no PDV** | Alta | Baixíssima | **Baixíssima** |
| **Garantia de Entrega** | Total (Rollback em cascata) | Alta (Broker) | **Total (Ledger + Fila de Retry)** |
| **Dependência de Infra** | Apenas Banco | Banco + RabbitMQ | **Apenas Banco** |
| **Escalabilidade de Leitura**| O(1) | O(1) | **O(1)** |

---

## 6. Estratégia de Validação & Testes de Carga/Concorrência (Verification & Testing)

> [!IMPORTANT]
> Este teste está implementado na suíte de testes de integração do `inv-service` e é executado contra um contêiner real do PostgreSQL 15 via Testcontainers.
> Classe de teste: [`ProjecaoRetryIntegrationTest`](../../apps/inv-service/src/test/java/inv/finance/ProjecaoRetryIntegrationTest.java)
> Comando de execução:
> ```bash
> mvn test -pl apps/inv-service -Dtest=ProjecaoRetryIntegrationTest
> ```

### Cenário de Teste Automatizado: *Simulação de Falha Transitória e Auto-Cura CQRS*
* **Classe:** `inv.finance.ProjecaoRetryIntegrationTest`
* **Configuração:** Testcontainers PostgreSQL 15 (`postgres:15-alpine`) com `FinancialProjectionListener` e `ProjectionRetryScheduler`.
* **Fluxo de Teste Validado:**
  1. Registrar uma venda e um lançamento contábil de R$ 150,00 no ledger (`lancamentos_financeiros`).
  2. **Injeção de Falha:** Forçar intencionalmente uma falha no `FinancialProjectionRepository.upsertProjection` durante a execução inicial via `@MockitoSpyBean`.
  3. **Validação Intermediária (Isolamento de Falha):**
     * O lançamento existe no `lancamentos_financeiros`.
     * O registro foi gravado na tabela `projection_retry_queue` com `status = 'PENDENTE'` e mensagem de erro serializada.
     * `financial_projection_venda` e `financial_projection_checkpoint` não refletem o valor devido ao rollback da transação de projeção.
  4. **Ativação da Auto-Cura:**
     * Restaurar o funcionamento normal do repositório (`Mockito.reset`) e disparar o `ProjectionRetryScheduler.reprocessarFalhasDeProjecao()`.
* **Critérios de Aceite Obrigatórios (Asserts Validados):**
  1. `projection_retry_queue` para o `lancamento_id` fica vazia (registro processado e deletado com sucesso).
  2. `financial_projection_venda` exibe `saldo = 150.00`, `total_creditos = 150.00` e `total_estornos = 0.00`.
  3. `financial_projection_checkpoint` contém o `lancamento_id` persistido (1 registro).
  4. Executar o scheduler uma segunda vez e validar que nenhuma operação duplicada é realizada (`saldo` permanece 150.00).

---

## 7. Referências e Links Relevantes

* [Listener de Projeções Financeiras](../../apps/inv-service/src/main/java/inv/finance/infrastructure/messaging/FinancialProjectionListener.java)
* [Scheduler de Auto-Cura](../../apps/inv-service/src/main/java/inv/finance/infrastructure/jobs/ProjectionRetryScheduler.java)
* [Repositório da Fila de Retry](../../apps/inv-service/src/main/java/inv/finance/infrastructure/persistence/ProjectionRetryRepository.java)
* [Documentação do Domínio Finance](../domains/finance.md)
