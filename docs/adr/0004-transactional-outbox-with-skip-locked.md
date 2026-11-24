# ADR-0004: Transactional Outbox Pattern com PostgreSQL `FOR UPDATE SKIP LOCKED`

* **Status:** Aceito
* **Data:** 2026-08-28
* **Autores:** Equipe de Engenharia
* **Bounded Contexts Afetados:** Shared, Checkout, Finance, Inventory
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
* **Prioridade por Criação:** A seleção prioriza o registro elegível mais antigo (`ORDER BY created_at ASC, id ASC`). Registros bloqueados ou em backoff podem ser ultrapassados.

---

## 3. Opções Consideradas (Considered Options)

1. **Opção 1: Publicação Direta com `@TransactionalEventListener(AFTER_COMMIT)`:**
   * *Problema:* Se o RabbitMQ estiver instável no instante do envio, a mensagem é perdida para sempre pois não há persistência de reenvio.
2. **Opção 2: Change Data Capture (CDC) com Debezium + Kafka Connect:**
   * *Problema:* Complexidade operacional excessiva para o estágio atual da infraestrutura.
3. **Opção 3: Transactional Outbox com Polling via `FOR UPDATE SKIP LOCKED` (Adotada):**
   * A entidade de negócio e o registro do evento na tabela `outbox` são gravados na **mesma transação ACID local**.
   * Um worker agendado consome a tabela `outbox` usando `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1`.
   * Publica a mensagem e remove o registro após confirmação do broker e verificação de roteamento. Se a tentativa falhar, o rollback preserva o registro para tratamento conforme a categoria de falha.

---

## 4. Decisão Tomada e Justificativa (Decision Outcome)

Decidimos adotar a **Opção 3: Transactional Outbox com `SKIP LOCKED`**, endurecida com confirmação obrigatória de publicação/roteamento AMQP, backoff e quarentena de falhas, agendamento ativo com isolamento de testes e observabilidade operacional.

### A. Tabela Outbox e Migrations Aditivas ([`V1__Schema_Inicial.sql`](../../apps/inv-service/src/main/resources/db/migration/V1__Schema_Inicial.sql#L47-L56), [`V10__outbox_resiliencia_entrega.sql`](../../apps/inv-service/src/main/resources/db/migration/V10__outbox_resiliencia_entrega.sql), [`V11__indice_outbox_elegibilidade.sql`](../../apps/inv-service/src/main/resources/db/migration/V11__indice_outbox_elegibilidade.sql))
* A tabela base criada na `V1` (`id`, `exchange`, `routing_key`, `payload`, `event_type`, `version`, `created_at`) recebeu as colunas aditivas da `V10`:
  * `status VARCHAR(20) NOT NULL DEFAULT 'PENDING'` com `CHECK (status IN ('PENDING', 'QUARANTINED'))`
  * `attempts INTEGER NOT NULL DEFAULT 0`, `next_attempt_at TIMESTAMP`, `last_attempt_at TIMESTAMP`
  * `last_error_type VARCHAR(64)`, `last_error TEXT`, `quarantined_at TIMESTAMP`
  * `replayed_at TIMESTAMP`, `replay_count INTEGER NOT NULL DEFAULT 0`
* A migration [`V11__indice_outbox_elegibilidade.sql`](../../apps/inv-service/src/main/resources/db/migration/V11__indice_outbox_elegibilidade.sql) (executada fora de bloco transacional via [`V11__indice_outbox_elegibilidade.sql.conf`](../../apps/inv-service/src/main/resources/db/migration/V11__indice_outbox_elegibilidade.sql.conf) com `executeInTransaction=false`) cria os índices parciais `idx_outbox_pending_created_at` (`ON outbox (created_at ASC, id ASC) INCLUDE (next_attempt_at) WHERE status = 'PENDING'`) e `idx_outbox_quarantined_at` (`ON outbox (quarantined_at DESC, id DESC) WHERE status = 'QUARANTINED'`).
* A entidade [`Outbox`](../../apps/inv-service/src/main/java/inv/shared/outbox/model/Outbox.java) inicializa `version = 1` e `status = "PENDING"`, enquanto o processamento e o replay preservam compatibilidade com registros legados `version = 0` e `version = 1`.

### B. Busca Sem Bloqueio Concorrente ([`OutboxRepository.java`](../../apps/inv-service/src/main/java/inv/shared/outbox/repository/OutboxRepository.java))
```sql
SELECT * FROM outbox
 WHERE status = 'PENDING'
   AND (next_attempt_at IS NULL OR next_attempt_at <= CAST(:agora AS TIMESTAMP))
 ORDER BY created_at ASC, id ASC
 LIMIT 1
 FOR UPDATE SKIP LOCKED;
```
* **O que faz o `SKIP LOCKED`:** Se a Instância A travar o registro elegível mais antigo, a Instância B pula esse registro e trava o próximo item elegível. Cada worker mantém o lock de linha durante a publicação, a espera pela confirmação e até o fim da transação.
* O instante `:agora` e os timestamps de `created_at`, backoff, quarentena e replay utilizam o bean `Clock` configurado em [`DateTimeConfig`](../../apps/inv-service/src/main/java/inv/shared/config/DateTimeConfig.java) (`America/Sao_Paulo`), injetado também nos três produtores `BEFORE_COMMIT` ([`PagamentoListener`](../../apps/inv-service/src/main/java/inv/checkout/infrastructure/messaging/PagamentoListener.java), [`VendaEstornadaOutboxListener`](../../apps/inv-service/src/main/java/inv/checkout/infrastructure/messaging/VendaEstornadaOutboxListener.java) e [`RabbitMQProducerListener`](../../apps/inv-service/src/main/java/inv/inventory/infrastructure/messaging/RabbitMQProducerListener.java)).

### C. Confirmação AMQP, Roteamento e Publicação Sem `__TypeId__` ([`OutboxDeliveryStep`](../../apps/inv-service/src/main/java/inv/shared/outbox/scheduler/OutboxDeliveryStep.java), [`RabbitMQConfig`](../../apps/inv-service/src/main/java/inv/shared/config/RabbitMQConfig.java))
* `application.properties` habilita `spring.rabbitmq.publisher-confirm-type=correlated`, `spring.rabbitmq.publisher-returns=true` e `spring.rabbitmq.template.mandatory=true`, com prazo máximo de confirmação `outbox.publisher.confirm-timeout-ms=3000`.
* `OutboxDeliveryStep` valida `version`, `eventType` e JSON contra a classe de evento e publica `Message` JSON UTF-8 persistente com `messageId = outbox-<id>`, `correlationId` único por tentativa (`outbox-<id>-<uuid>`), headers `x-outbox-id` e `x-event-version` e **sem header `__TypeId__`**, desacoplando contratos entre serviços e aceitando `version = 0` e `1`.
* A linha só é excluída (`delete` + `flush` em `Propagation.REQUIRES_NEW`) após `Confirm(ack=true)` e `CorrelationData.getReturned() == null`. O publisher ACK confirma o aceite pelo broker. Uma mensagem sem rota pode receber ACK junto de um return `NO_ROUTE`, por isso as duas condições são verificadas. O ACK do consumidor é independente. Em `NO_ROUTE`, `NACK`, fechamento `404 NOT_FOUND`, timeout ou falha de conexão, a transação sofre rollback e o registro permanece preservado.
* A fila órfã `inventory.v1.stock-low.notify` (e sua DLX/DLQ) fica condicionada por `inv.rabbitmq.legacy-low-stock-queue.enabled=false`, evitando que mensagens de estoque baixo sejam absorvidas sem consumidor ativo quando o `mail-service` não declarou `inventory.v1.low-stock.send-email`.

### D. Agendamento, Isolamento de Falhas, Replay Admin e Observabilidade
* **Scheduling isolável:** [`SchedulingConfig`](../../apps/inv-service/src/main/java/inv/shared/config/SchedulingConfig.java) ativa `@EnableScheduling` sob `inv.scheduling.enabled=true` (falso nos testes `@SpringBootTest` que não exercitam agendamento) e [`OutboxScheduler`](../../apps/inv-service/src/main/java/inv/shared/outbox/scheduler/OutboxScheduler.java) obedece a `outbox.scheduler.enabled=true`.
* **Separação pós-rollback:** [`OutboxProcessor`](../../apps/inv-service/src/main/java/inv/shared/outbox/scheduler/OutboxProcessor.java) (não transacional) coordena `OutboxDeliveryStep` (`REQUIRES_NEW`) e aciona [`OutboxFailureRecorder`](../../apps/inv-service/src/main/java/inv/shared/outbox/scheduler/OutboxFailureRecorder.java) (`REQUIRES_NEW` via `UPDATE` condicional `WHERE id = :id AND status = 'PENDING'`) quando ocorre rollback:
  * *Poison pills* (`ClassNotFoundException`, JSON inválido, versão negativa): movidos imediatamente para `QUARANTINED` e o ciclo segue (`FAILED_CONTINUE`).
  * Falhas de roteamento (`NO_ROUTE`, `404 NOT_FOUND`): agendados com backoff exponencial via `Clock` até `outbox.retry.max-attempts` (quando passam a `QUARANTINED`) e o ciclo segue (`FAILED_CONTINUE`).
  * Broker indisponível (`AmqpConnectException`, timeout, `basic.nack` genérico) ou erro de persistência pós-confirmação AMQP: pausam o ciclo (`FAILED_PAUSE`). O mesmo teto de tentativas se aplica a essas falhas. Abaixo do teto, o registro recebe backoff. Ao atingi-lo, vai para quarentena.
* **Inspeção e replay administrativo:** [`OutboxAdminController`](../../apps/inv-service/src/main/java/inv/shared/outbox/web/OutboxAdminController.java) e [`OutboxReplayService`](../../apps/inv-service/src/main/java/inv/shared/outbox/service/OutboxReplayService.java) expõem `GET /admin/outbox/quarantined` (teto `size <= 100`), `GET /admin/outbox/{id}` e `POST /admin/outbox/{id}/replay` atômico, restritos a JWT com `ROLE_ADMIN` no `inv-service`. O POST atua sobre uma linha em quarentena por ID. Retorna `200` ao recolocá-la em `PENDING` para nova tentativa, `409` se a linha estiver fora da quarentena e `404` se estiver ausente, inclusive após drenagem bem-sucedida. Os erros usam RFC 7807.
* **Métricas e log operacional ([`OutboxMetrics`](../../apps/inv-service/src/main/java/inv/shared/outbox/metrics/OutboxMetrics.java)):** expõe via Micrometer/Prometheus os gauges `outbox.oldest.eligible.age.seconds` (`GREATEST(0, ...)` via `Clock`, tolerante a erro transitório de banco sem degradar `/actuator/prometheus`) e `outbox.quarantined.count`, os counters `outbox.quarantined.total` (tag `error_type`), `outbox.replay.requested.total` e `outbox.replay.completed.total`, além do log estruturado `event=outbox_replay_completed` ao concluir entrega com `replay_count > 0`.

---

## 5. Prós, Contras e Semântica de Entrega por Consumidor

| Padrão | Garantia de Entrega | Suporte Multi-Instância | Sobrecarga de Infra | Complexidade |
| :--- | :--- | :--- | :--- | :--- |
| **Envio Síncrono Direto** | Fraca (Risco de perda) | N/A | Zero | Baixíssima |
| **CDC (Debezium + Kafka)** | Altíssima | Altíssima | Altíssima (Kafka Connect) | Muito Alta |
| **Outbox com SKIP LOCKED** | ***At-least-once* com retentativas e quarentena** | **Workers paralelos com locks de linha** | **Mínima (tabela no PostgreSQL existente)** | **Média** |

### Garantia Real de Entrega (*At-Least-Once*) e Limites por Consumidor
A semântica *at-least-once* admite múltiplas publicações da mesma linha. Como a exclusão da linha ocorre em transação local do PostgreSQL depois da confirmação do broker, um rollback nessa janela preserva a linha. Uma nova tentativa pode produzir outra entrega AMQP. Se as falhas persistirem até o teto, a linha fica em quarentena e a retomada depende de replay administrativo. Os efeitos no destino dependem do consumidor:

| Consumidor | Rota / Fila | Garantia Efetiva | Mecanismo e Limite Conhecido |
| :--- | :--- | :--- | :--- |
| **Checkout** ([`VendaEventHandler`](../../apps/inv-service/src/main/java/inv/checkout/infrastructure/messaging/VendaEventHandler.java)) | `pdv.venda.pagamento.confirmado.queue` (+ `.dlq`) | **Idempotente** | Lock pessimista `findByIdWithLock` em `Venda` e verificação de flags `contabilizado`, `excedente` e status `ESTORNADO` antes de mutar ou publicar eventos. |
| **Finance** ([`VendaEstornadaEventListener`](../../apps/inv-service/src/main/java/inv/finance/infrastructure/messaging/VendaEstornadaEventListener.java)) | `pdv.venda.estornada.queue` (+ `.dlq`) | **Efeito contábil exatamente único** | Chave determinística `uuidReferenciaEstorno` (`UUID.nameUUIDFromBytes("ESTORNO:" + vendaId)`) aliada a `INSERT ... ON CONFLICT DO NOTHING RETURNING id`, garantindo 1 único lançamento `ESTORNO` e atualização única de projeções mesmo sob reentrega real. |
| **Mail** ([`EmailConsumer`](../../apps/mail-service/src/main/java/mail/service/EmailConsumer.java)) | `inventory.v1.low-stock.send-email` (sem DLQ/retry) | **Melhor esforço deduplicado (sujeito ao limite físico SMTP)** | Deduplicação via `processed_events(eventId)`, porém sem 2PC com SMTP: `existsByEventId` (sem lock) executa antes de `mailSender.send` e `processedEventRepository.save` ocorre depois, permitindo reenvio em falha pós-SMTP ou entrega concorrente. Além disso, [`mail.config.RabbitMQConfig`](../../apps/mail-service/src/main/java/mail/config/RabbitMQConfig.java) declara `inventory.v1.low-stock.send-email` sem DLX/DLQ e sem política de retry. |

---

## 6. Estratégia de Validação & Testes (Verification & Testing)

> [!IMPORTANT]
> As suítes de integração da outbox executam contra contêineres reais do PostgreSQL 15 e RabbitMQ 3 via Testcontainers (Failsafe).
> Comando portável de execução da regressão concorrente e prova E2E de recuperação:
> ```bash
> mvn verify -pl apps/inv-service -am '-Dtest=!*' "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=OutboxConcorrenciaIntegrationTest,OutboxEstornoRecuperacaoIT" "-Dfailsafe.failIfNoSpecifiedTests=false"
> ```

### A. Stress Test de Workers Paralelos ([`OutboxConcorrenciaIntegrationTest`](../../apps/inv-service/src/test/java/inv/shared/outbox/OutboxConcorrenciaIntegrationTest.java))
* **Configuração:** `inv.scheduling.enabled=false`, Testcontainers PostgreSQL 15 (`postgres:15-alpine`) e RabbitMQ 3 (`rabbitmq:3-management`).
* **Massa e Sincronismo:** **100 mensagens** (`eventType = "java.util.HashMap"`, `version = 1`) na tabela `outbox` e **10 workers concorrentes** (`ExecutorService` + `CountDownLatch(1)` / `CountDownLatch(10)`) chamando `OutboxProcessor.processNext()` em `adr0004.outbox.queue`.
* **Critérios Validados:** exatamente **100 mensagens únicas** entregues no RabbitMQ, **0 linhas** restantes na `outbox` e zero deadlocks (`PSQLException`) ou exceções.

### B. Prova Ponta a Ponta de Recuperação e Operação ([`OutboxEstornoRecuperacaoIT`](../../apps/inv-service/src/test/java/inv/it/OutboxEstornoRecuperacaoIT.java))
* **Ciclo Completo Real:** `POST /vendas/{id}/cancelar` → `VendaEstornadaOutboxListener` (`BEFORE_COMMIT`) → `outbox` → `OutboxProcessor` → RabbitMQ real → `VendaEstornadaEventListener` → `lancamentos_financeiros` + projeções `financial_projection_venda` e `financial_projection_diaria`.
* **Janela Pós-Confirm / Pré-Commit:** falha determinística em `outboxRepository.delete(...)` após `Confirm(ack=true)` comprova que a 1ª entrega real chega ao consumidor enquanto o rollback mantém a linha `PENDING` (`attempts = 1`), e a 2ª entrega via broker real resulta em exatamente **1 lançamento `ESTORNO` (`-100.00`)** e projeções conciliadas (em contraste com falha pré-envio, que produz `0` entregas antes da retomada).
* **Observabilidade Operacional:** comprova `/actuator/prometheus` (`outbox_oldest_eligible_age_seconds` não negativo e resiliente a erro transitório de banco, `outbox_quarantined_count`, `outbox_quarantined_total`, `outbox_replay_requested_total`, `outbox_replay_completed_total`), listagem/replay em `/admin/outbox/**` (`404` pós-drenagem) e log `event=outbox_replay_completed`.

### C. Suítes Complementares de Confirmação, Resiliência e Agendamento
* [`OutboxConfirmacaoERotasIT`](../../apps/inv-service/src/test/java/inv/it/OutboxConfirmacaoERotasIT.java): `NO_ROUTE`, `404 NOT_FOUND`, compatibilidade `version = 0`/`1` sem `__TypeId__` e isolamento da fila órfã de estoque.
* [`OutboxResilienciaEReplayIT`](../../apps/inv-service/src/test/java/inv/it/OutboxResilienciaEReplayIT.java): quarentena sem bloqueio de cabeça de fila (*head-of-line*), backoff determinístico com `Clock`, replay concorrente (`200`/`409`), segurança `ROLE_ADMIN` e índices `V11`.
* [`OutboxSchedulingRetomadaIT`](../../apps/inv-service/src/test/java/inv/it/OutboxSchedulingRetomadaIT.java): `@EnableScheduling` ativo, drenagem automática e retomada de linhas vencidas após reinício do contexto.
* Utilitário manual local (dev): [`OutboxConcurrencyManualRunner`](../../apps/inv-service/src/test/java/inv/scheduler/OutboxConcurrencyManualRunner.java).

---

## 7. Adendo (Novembro/2026 — DEBT-ARCH-006): Limites do Replay Administrativo

O replay reabilita uma linha em quarentena, zera as tentativas, torna a linha elegível imediatamente e atualiza `replayed_at` e `replay_count`. Exchange, rota, payload, versão e diagnóstico da falha são preservados. O sucesso do POST confirma a reativação da linha. A publicação ocorre no processamento posterior da outbox e pode repetir uma entrega já aceita pelo broker.

O endpoint é acessado diretamente no `inv-service`, sem rota dedicada no `api-gateway`. O [Compose](../../docker-compose.yml) publica `8083:8083`, portanto a ausência de rota no gateway não restringe o acesso à rede interna. Em redes que alcançam a porta publicada, o endpoint fica acessível fora dos controles do gateway e depende da validação do JWT e de `ROLE_ADMIN` pelo próprio serviço.

---

## 8. Referências e Links Relevantes

* **Núcleo da Outbox (`inv.shared.outbox`):**
  * [Outbox](../../apps/inv-service/src/main/java/inv/shared/outbox/model/Outbox.java)
  * [OutboxRepository](../../apps/inv-service/src/main/java/inv/shared/outbox/repository/OutboxRepository.java)
  * [OutboxProcessor](../../apps/inv-service/src/main/java/inv/shared/outbox/scheduler/OutboxProcessor.java)
  * [OutboxDeliveryStep](../../apps/inv-service/src/main/java/inv/shared/outbox/scheduler/OutboxDeliveryStep.java)
  * [OutboxFailureRecorder](../../apps/inv-service/src/main/java/inv/shared/outbox/scheduler/OutboxFailureRecorder.java)
  * [OutboxScheduler](../../apps/inv-service/src/main/java/inv/shared/outbox/scheduler/OutboxScheduler.java)
  * [OutboxReplayService](../../apps/inv-service/src/main/java/inv/shared/outbox/service/OutboxReplayService.java)
  * [OutboxAdminController](../../apps/inv-service/src/main/java/inv/shared/outbox/web/OutboxAdminController.java)
  * [OutboxMetrics](../../apps/inv-service/src/main/java/inv/shared/outbox/metrics/OutboxMetrics.java)
* **Configuração e Migrations:**
  * [RabbitMQConfig (`inv-service`)](../../apps/inv-service/src/main/java/inv/shared/config/RabbitMQConfig.java)
  * [SchedulingConfig](../../apps/inv-service/src/main/java/inv/shared/config/SchedulingConfig.java)
  * [DateTimeConfig](../../apps/inv-service/src/main/java/inv/shared/config/DateTimeConfig.java)
  * [V1__Schema_Inicial.sql](../../apps/inv-service/src/main/resources/db/migration/V1__Schema_Inicial.sql)
  * [V10__outbox_resiliencia_entrega.sql](../../apps/inv-service/src/main/resources/db/migration/V10__outbox_resiliencia_entrega.sql)
  * [V11__indice_outbox_elegibilidade.sql](../../apps/inv-service/src/main/resources/db/migration/V11__indice_outbox_elegibilidade.sql)
* **Produtores, Consumidores e Documentação:**
  * [PagamentoListener](../../apps/inv-service/src/main/java/inv/checkout/infrastructure/messaging/PagamentoListener.java)
  * [VendaEstornadaOutboxListener](../../apps/inv-service/src/main/java/inv/checkout/infrastructure/messaging/VendaEstornadaOutboxListener.java)
  * [RabbitMQProducerListener](../../apps/inv-service/src/main/java/inv/inventory/infrastructure/messaging/RabbitMQProducerListener.java)
  * [VendaEventHandler](../../apps/inv-service/src/main/java/inv/checkout/infrastructure/messaging/VendaEventHandler.java)
  * [VendaEstornadaEventListener](../../apps/inv-service/src/main/java/inv/finance/infrastructure/messaging/VendaEstornadaEventListener.java)
  * [EmailConsumer (`mail-service`)](../../apps/mail-service/src/main/java/mail/service/EmailConsumer.java)
  * [RabbitMQConfig (`mail-service`)](../../apps/mail-service/src/main/java/mail/config/RabbitMQConfig.java)
  * [DEBT-ARCH-006 — Entrega e Recuperação de Eventos da Outbox](../debt/DEBT-ARCH-006/README.md)
  * [Microservices.io - Transactional Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html)
