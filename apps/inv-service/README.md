# 📦 Inv Service (Core Domain & Monólito Modular)

O **Inv Service** é o núcleo de regras de negócio do Pet Shop, estruturado como um **Monólito Modular** (validado por ArchUnit e Spring Modulith) contendo os subdomínios de **Checkout**, **Dashboard**, **Finance** e **Inventory**.

* **Porta Padrão:** `8083`
* **Stack:** Java 21, Spring Boot 3.4.1, Spring Modulith 1.3.2, PostgreSQL 15, Flyway, RabbitMQ, Redis, Spring Security Resource Server.

---

## 🏛️ Submódulos e Bounded Contexts

```plaintext
src/main/java/inv/
├── checkout/       # Vendas, Pagamentos, Webhooks e Long Polling de PDV
├── dashboard/      # Módulo de Composição de Leitura (BFF Interno Read-Only)
├── finance/        # Ledger Append-Only, Projeções CQRS, High Watermark e Fechamento D+0
├── inventory/      # Catálogo de Produtos, Baixas Atômicas em Lote Ordenado e Alertas Anti-Spam
└── shared/         # Transactional Outbox, Configurações de Banco, RabbitMQ e Segurança
```

---

## 🚀 Endpoints Principais (Checkout & Webhooks)

* `POST /vendas` - Abertura de venda e reserva atômica de itens em lote ordenado (`produtoId ASC`).
* `GET /vendas/{id}/status` - Long Polling não-bloqueante (`DeferredResult`) do status da venda para PDVs em checkout.
* `POST /pagamentos/intencao/{vendaId}` - Registro de intenção de pagamento com UUID de idempotência.
* `GET /pagamentos/{id}/status` - Consulta do status da intenção ou confirmação de pagamento.
* `POST /pagamentos/webhook/confirmar/{pagamentoId}` - Webhook de confirmação de pagamento recebido de adquirentes com validação de payload via `ConfirmarPagamentoWebhookRequest` (`@Positive valor_recebido`, `@NotBlank nsu`; respostas RFC 7807 em HTTP 400).
* `POST /pagamentos/webhook/falhar/{pagamentoId}` - Webhook para sinalização de recusa ou falha de processamento na operadora.

---

## 📖 Documentação Detalhada

* 📑 **[Visão Geral da Arquitetura](../../docs/architecture/system-overview.md)**
* 🛒 **[Deep Dive: Bounded Context de Checkout](../../docs/domains/checkout.md)**
* 💰 **[Deep Dive: Bounded Context Financeiro](../../docs/domains/finance.md)**
* 📦 **[Deep Dive: Bounded Context de Estoque](../../docs/domains/inventory.md)**

### ADRs Relevantes
* [ADR-0001: Monólito Modular](../../docs/adr/0001-modular-monolith-bounded-contexts.md)
* [ADR-0002: Concorrência Híbrida & Estoque Atômico](../../docs/adr/0002-atomic-sql-inventory-and-hybrid-locking.md)
* [ADR-0003: Long Polling Não-Bloqueante](../../docs/adr/0003-async-long-polling-pos-deferred-result.md)
* [ADR-0004: Transactional Outbox com SKIP LOCKED](../../docs/adr/0004-transactional-outbox-with-skip-locked.md)
* [ADR-0005: Ledger Financeiro Append-Only](../../docs/adr/0005-append-only-financial-ledger-and-idempotency.md)
* [ADR-0006: Projeções CQRS e Auto-Cura](../../docs/adr/0006-cqrs-asynchronous-projections-and-self-healing-retry.md)
* [ADR-0007: Fechamento de Caixa D+0 com High Watermark](../../docs/adr/0007-d0-daily-cash-closing-and-high-watermark.md)
* [ADR-0010: Dashboard como Módulo de Composição de Leitura](../../docs/adr/0010-dashboard-read-only-composition-module.md)
* [ADR-0011: Open Host Service Síncrono de Comandos e Catálogo (Checkout → Inventory)](../../docs/adr/0011-synchronous-open-host-service-checkout-inventory.md)

---

## 🛠️ Execução e Variáveis de Ambiente

```properties
server.port=8083
spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:petshop_db}
spring.datasource.username=${DB_USER:postgres}
spring.datasource.password=${DB_PASS:postgres}
jwt.public.key=${JWT_PUBLIC_KEY}
spring.rabbitmq.host=${RABBITMQ_HOST:localhost}
```
