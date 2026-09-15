# 📦 Inv Service (Core Domain & Monólito Modular)

O **Inv Service** é o núcleo de regras de negócio do Pet Shop, estruturado como um **Monólito Modular** contendo os subdomínios de **Checkout**, **Finance** e **Inventory**.

* **Porta Padrão:** `8083`
* **Stack:** Java 21, Spring Boot 3.4.1, PostgreSQL 15, Flyway, RabbitMQ, Spring Security Resource Server.

---

## 🏛️ Submódulos e Bounded Contexts

```plaintext
src/main/java/inv/
├── checkout/       # Vendas, Pagamentos, Webhooks e Long Polling de PDV
├── finance/        # Ledger Append-Only, Projeções CQRS, High Watermark e Fechamento D+0
├── inventory/      # Catálogo de Produtos, Baixas Atômicas e Alertas Anti-Spam
└── shared/         # Transactional Outbox, Configurações de Banco, RabbitMQ e Segurança
```

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
