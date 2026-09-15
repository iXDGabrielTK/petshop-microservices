# 📨 Mail Service (Consumidor Assíncrono de Notificações)

O **Mail Service** é o microsserviço responsável pelo processamento assíncrono e disparo de e-mails transacionais (Recuperação de Senha, Alertas de Estoque Baixo).

* **Porta Padrão:** `8082`
* **Stack:** Java 21, Spring Boot 3.4.1, Spring AMQP (RabbitMQ), JavaMailSender (Mailtrap / SMTP), JPA / Hibernate (`processed_events`).

---

## ⚡ Destaques de Engenharia & Pendências de Produção

* **Idempotent Consumer:** Deduplicação no nível de banco via tabela `processed_events` baseada no `eventId` da mensagem.
* **Schema Evolution:** Suporte estrutural a versionamento (`v1`, `v2`).
* **Resiliência:** Retries automáticos com backoff exponencial e Dead Letter Queues (DLQ).

> [!WARNING]
> **Aviso de Governança (Stub V2 em Produção):** O processamento de mensagens versão `v2` em `EmailConsumer.java` está atualmente implementado como um **stub** que executa o fallback silencioso para a lógica `V1` com um log de advertência.
> 
> **Item de Acompanhamento (Tracking Item):**
> * **`[DEBT-MAIL-001]`**: Implementar métodos dedicados `processarResetSenhaV2` e `processarEstoqueV2` antes de habilitar publicadores em versão 2 no broker.

---

## 📖 Documentação Detalhada

* 📑 **[Visão Geral da Arquitetura](../../docs/architecture/system-overview.md)**
* 📑 **[ADR-0008: Consumidor Idempotente e Evolução de Schemas](../../docs/adr/0008-distributed-idempotent-consumer-and-schema-evolution.md)**
