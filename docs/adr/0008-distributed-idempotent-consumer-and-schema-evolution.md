# ADR-0008: Consumidor Idempotente e Evolução de Schemas em Mensageria AMQP

* **Status:** Aceito
* **Data:** 2026-08-28
* **Autores:** Equipe de Engenharia
* **Bounded Contexts Afetados:** Shared, Mail, Auth, Inventory
* **Serviços:** `apps/mail-service`, `apps/auth-service`, `apps/inv-service`

---

## 1. Contexto e Problema (Context & Problem Statement)

Brokers de mensageria como o RabbitMQ operam sob a garantia de entrega **At-Least-Once (Pelo menos uma vez)**, e não *Exactly-Once*.

Cenários comuns na rede distribuída causam entregas duplicadas:
1. O consumidor processa o envio de um e-mail transacional (ex: Recuperação de Senha ou Alerta de Estoque Baixo), mas a conexão de rede oscila antes que o comando `basic.ack` atinja o broker.
2. O RabbitMQ reencaminha a mensagem (*re-queue*) para outro consumidor disponível.
3. Sem uma estratégia defensiva, o consumidor reenviará o e-mail para o cliente final, gerando desconfiança e spam.

Além disso, contratos de eventos mudam ao longo do tempo (ex: adição de novos campos ou renomeação de propriedades). Em deploys sem downtime (*rolling deployments*), consumidores antigos e novos produtores precisam coexistir sem quebrar a comunicação.

---

## 2. Drivers de Decisão (Decision Drivers / Forces)

* **Eliminação de Efeitos Colaterais Duplicados:** Nenhuma notificação ou ação transacional externa (SMTP) pode ocorrer mais de uma vez para o mesmo identificador de evento (`eventId`).
* **Resiliência a Falhas de ACK do Broker:** Se uma mensagem já processada for reenviada, ela deve ser descartada silenciosamente com confirmação imediata.
* **Compatibilidade Retroativa (Backward Compatibility):** O consumidor deve ser capaz de processar mensagens em versões anteriores (`v1`) e possuir fallbacks seguros para novas versões (`v2`).

---

## 3. Opções Consideradas (Considered Options)

1. **Opção 1: Confiar no Broker / Deduplicação em Memória:** Usar caches efêmeros ou confiar que o RabbitMQ não duplicará mensagens.
   * *Problema:* Caches em memória são perdidos em restarts de containers e não funcionam entre réplicas diferentes.
2. **Opção 2: Idempotência Apenas no Nível da Aplicação Externa:** Exigir que o provedor de e-mail (Mailtrap/SendGrid) deduplique mensagens.
   * *Problema:* Alto acoplamento com provedores de terceiros e suporte limitado.
3. **Opção 3: Idempotent Receiver Pattern com Tabela `processed_events` + Schema Evolution Explícito (Adotada):**
   * Cada mensagem carrega `eventId` (UUID) e `version` (int).
   * O consumidor valida e registra o `eventId` na tabela persistida `processed_events`.
   * Um switch-case de versionamento direciona o payload para o deserializador/processador correto com fallback defensivo.

---

## 4. Decisão Tomada e Justificativa (Decision Outcome)

Decidimos adotar a **Opção 3: Idempotent Consumer com Tabela de Controle e Evolução de Schemas**.

### A. Deduplicação Física no Consumidor ([`EmailConsumer.java`](../../apps/mail-service/src/main/java/mail/service/EmailConsumer.java#L37-L63))
```java
@Transactional
@RabbitListener(queues = RabbitMQConfig.QUEUE_NAME)
public void receivePasswordResetMessage(PasswordResetMessage message) {
    if (isDuplicated(message.getEventId())) return;

    try {
        switch (message.getVersion()) {
            case 1: processarResetSenhaV1(message); break;
            case 2:
                logger.warn("Versão 2 não implementada. Usando fallback V1.");
                processarResetSenhaV1(message);
                break;
            default:
                logger.warn("Versão desconhecida {}. Usando fallback V1.", message.getVersion());
                processarResetSenhaV1(message);
        }

        markAsProcessed(message.getEventId(), "PASSWORD_RESET");
    } catch (Exception e) {
        logger.error("Falha no processamento, disparando NACK para retry", e);
        throw e;
    }
}
```

> [!WARNING]
> **Aviso de Governança & Risco de Produção (Stub V2):** No código atual de [`EmailConsumer.java`](../../apps/mail-service/src/main/java/mail/service/EmailConsumer.java), o tratamento de mensagens `v2` (tanto para reset de senha quanto para estoque baixo) é um **stub provisório** que executa o fallback silencioso para a lógica `V1` emitindo apenas um `logger.warn`. Em um cenário real de produção com eventos `v2` contendo campos adicionais ou reestruturados, dados específicos da versão 2 serão descartados ou mal interpretados.
> 
> **Item de Acompanhamento (Tracking Item):**
> * **[DEBT-MAIL-001]** Implementar handlers de negócio dedicados (`processarResetSenhaV2` e `processarEstoqueV2`) para deserialização e enriquecimento completo dos contratos V2, eliminando o fallback degradado antes do deploy de produtores V2.

### B. Tabela de Deduplicação (`ProcessedEvent`)
* `eventId` é a Chave Primária (`PRIMARY KEY`).
* A checagem rápida (`existsByEventId`) evita processamento desnecessário, e o `save(ProcessedEvent)` finaliza o ciclo de vida na mesma transação.

---

## 5. Prós e Contras das Opções Analisadas

* **Positivas (+):**
  * Imunidade total a duplicações de entrega do broker.
  * Suporte a deploys graduais e evolução contínua dos contratos de eventos.
  * Não depende de recursos específicos proprietários do broker (funciona com RabbitMQ, Kafka ou SQS).
* **Negativas / Desafios (-):**
  * Custo de uma consulta (`SELECT`) e uma inserção (`INSERT`) adicionais no banco por mensagem consumida.
  * A tabela `processed_events` deve receber uma política periódica de expurgo (*retention policy* / TTL de 30-90 dias) em ambientes de altíssimo volume.

---

## 6. Estratégia de Validação & Testes de Carga/Concorrência (Verification & Testing)

> [!IMPORTANT]
> Este teste está implementado na suíte de testes de integração do `mail-service` e é executado contra contêineres reais do PostgreSQL 15 e RabbitMQ 3 via Testcontainers.
> Classe de teste: [`EmailConsumerIdempotenciaIntegrationTest`](../../apps/mail-service/src/test/java/mail/service/EmailConsumerIdempotenciaIntegrationTest.java)
> Comando de execução:
> ```bash
> mvn test -pl apps/mail-service -Dtest=EmailConsumerIdempotenciaIntegrationTest
> ```

### Cenário de Teste Automatizado: *Simulação de Entregas Duplicadas Simultâneas*
* **Classe:** `mail.service.EmailConsumerIdempotenciaIntegrationTest`
* **Configuração:** Testcontainers com RabbitMQ 3 (`rabbitmq:3-management`) e PostgreSQL 15 (`postgres:15-alpine`).
* **Massa de Teste:**
  * Criação de uma mensagem com `eventId = "uuid-teste-dedup-123"`.
  * Publicação de **10 cópias idênticas** dessa mesma mensagem na fila `auth.v1.password-reset.send-email` (exchange `auth.v1.events`, routing key `auth.password.reset`).
* **Mocks:**
  * `JavaMailSender` mockado via `@MockitoBean` para capturar e verificar contagem de chamadas ao método `.send()`.

### Critérios de Aceite Obrigatórios (Asserts Validados)
1. **Chamadas ao Provedor de E-mail:** O método `mailSender.send()` é invocado **exatamente 1 vez**.
2. **Registro de Deduplicação:** A tabela `processed_events` contém **exatamente 1 linha** correspondente ao `eventId`.
3. **Comportamento das Mensagens Duplicadas:** As 9 mensagens redundantes são reconhecidas (`ACK`) e descartadas com log de advertência sem lançar exceções.

---

## 7. Referências e Links Relevantes

* [EmailConsumer](../../apps/mail-service/src/main/java/mail/service/EmailConsumer.java)
* [ProcessedEvent Entity](../../apps/mail-service/src/main/java/mail/model/ProcessedEvent.java)
* [RabbitMQConfig](../../apps/mail-service/src/main/java/mail/config/RabbitMQConfig.java)
