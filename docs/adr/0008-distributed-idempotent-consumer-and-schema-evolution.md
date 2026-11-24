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

* **Deduplicação por Evento:** Evitar repetir o envio SMTP quando o mesmo identificador de evento (`eventId`) já estiver registrado.
* **Resiliência a Falhas de ACK do Broker:** Se uma mensagem cujo processamento foi confirmado no banco for reenviada, o consumidor a descarta com retorno normal.
* **Compatibilidade Retroativa (Backward Compatibility):** O consumidor deve ser capaz de processar mensagens em versões anteriores (`v1`) e possuir fallbacks seguros para novas versões (`v2`).

---

## 3. Opções Consideradas (Considered Options)

1. **Opção 1: Confiar no Broker / Deduplicação em Memória:** Usar caches efêmeros ou confiar que o RabbitMQ não duplicará mensagens.
   * *Problema:* Caches em memória são perdidos em restarts de containers e não funcionam entre réplicas diferentes.
2. **Opção 2: Idempotência Apenas no Nível da Aplicação Externa:** Exigir que o provedor de e-mail (Mailtrap/SendGrid) deduplique mensagens.
   * *Problema:* Alto acoplamento com provedores de terceiros e suporte limitado.
3. **Opção 3: Idempotent Receiver Pattern com Tabela `processed_events` + Schema Evolution Explícito (Adotada):**
   * Os contratos incluem `eventId` (string) e `version` (int).
   * O consumidor consulta e registra o `eventId` na tabela persistida `processed_events` quando o identificador está presente.
   * Após a desserialização, um switch seleciona o processamento de negócio por versão.

---

## 4. Decisão Tomada e Justificativa (Decision Outcome)

Decidimos adotar a **Opção 3: Idempotent Consumer com Tabela de Controle e Evolução de Schemas**.

### A. Processamento e Deduplicação no Consumidor ([`EmailConsumer.java`](../../apps/mail-service/src/main/java/mail/service/EmailConsumer.java))
`receivePasswordResetMessage` e `receiveLowStockMessage` consultam `isDuplicated` antes de enviar e chamam `markAsProcessed` depois de `mailSender.send`. Falhas de processamento são registradas e propagadas ao contêiner AMQP.

> [!WARNING]
> **Limite do Fallback V2:** Reset de senha e estoque baixo usam a lógica V1 com log de advertência para versões sem handler dedicado. A compatibilidade depende da estrutura JSON esperada pelo DTO local.
> 
> **Item de Acompanhamento (Tracking Item):**
> * **[DEBT-MAIL-001]** Implementar handlers de negócio dedicados (`processarResetSenhaV2` e `processarEstoqueV2`) para deserialização e enriquecimento completo dos contratos V2, eliminando o fallback degradado antes do deploy de produtores V2.

### B. Tabela de Deduplicação (`ProcessedEvent`)
* `eventId` é a Chave Primária (`PRIMARY KEY`).
* A checagem `existsByEventId` ignora entregas posteriores ao registro confirmado. O `save(ProcessedEvent)` ocorre depois do envio. O banco e o SMTP têm confirmações independentes. Duas entregas concorrentes podem passar pela consulta antes do primeiro commit e disparar dois envios. Uma falha após o envio e antes do commit também permite novo envio na reentrega.

---

## 5. Prós e Contras das Opções Analisadas

* **Positivas (+):**
  * Deduplicação persistente de mensagens que já têm registro confirmado.
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
> mvn verify -pl apps/mail-service -am '-Dtest=!*' "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=EmailConsumerIdempotenciaIntegrationTest,EmailConsumerEstoqueBaixoSemTypeIdIT" "-Dfailsafe.failIfNoSpecifiedTests=false"
> ```

### Cenário de Teste Automatizado: *Deduplicação de Cópias de Reset de Senha*
* **Classe:** `mail.service.EmailConsumerIdempotenciaIntegrationTest`
* **Configuração:** Testcontainers com RabbitMQ 3 (`rabbitmq:3-management`) e PostgreSQL 15 (`postgres:15-alpine`).
* **Massa de Teste:**
  * Criação de uma mensagem com `eventId = "uuid-teste-dedup-123"`.
  * Publicação em sequência, num único laço, de **10 cópias idênticas** na fila `auth.v1.password-reset.send-email` (exchange `auth.v1.events`, routing key `auth.password.reset`).
* **Mocks:**
  * `JavaMailSender` mockado via `@MockitoBean` para capturar e verificar contagem de chamadas ao método `.send()`.

### Critérios de Aceite Obrigatórios (Asserts Validados)
1. **Chamadas ao Provedor de E-mail:** O método `mailSender.send()` é invocado **exatamente 1 vez**.
2. **Registro de Deduplicação:** A tabela `processed_events` contém **exatamente 1 linha** correspondente ao `eventId`.
3. **Estabilidade:** Durante a janela de observação, as contagens de envios e registros permanecem em 1. O teste observa esses dois efeitos com o SMTP mockado.

---

## 7. Adendo (Novembro/2026 — DEBT-ARCH-006): Compatibilidade JSON sem `__TypeId__`

O alerta de estoque baixo publicado pela outbox chega como JSON UTF-8 sem `__TypeId__`. O `Jackson2JsonMessageConverter` do `mail-service` usa o tipo inferido da assinatura de `receiveLowStockMessage(EstoqueBaixoMessage)`. O DTO local interpreta os campos do payload, incluindo `eventId` e `version`, sem depender de uma classe Java compartilhada com o produtor.

[`EmailConsumerEstoqueBaixoSemTypeIdIT`](../../apps/mail-service/src/test/java/mail/service/EmailConsumerEstoqueBaixoSemTypeIdIT.java) publica um alerta JSON sem esse header em RabbitMQ real e verifica o conteúdo do e-mail, uma chamada ao `JavaMailSender` mockado e o registro de deduplicação no PostgreSQL. O limite de deduplicação descrito na seção 4 vale também para esse fluxo. A [ADR-0004](0004-transactional-outbox-with-skip-locked.md) registra as condições de publicação e de reentrega.

---

## 8. Referências e Links Relevantes

* [EmailConsumer](../../apps/mail-service/src/main/java/mail/service/EmailConsumer.java)
* [ProcessedEvent Entity](../../apps/mail-service/src/main/java/mail/model/ProcessedEvent.java)
* [RabbitMQConfig](../../apps/mail-service/src/main/java/mail/config/RabbitMQConfig.java)
