# 🏛️ Arquitetura do Sistema & Visão Geral (System Overview)

Este documento fornece a visão arquitetural consolidada do ecossistema **Pet Shop Microservices**, cobrindo o modelo C4 de containers, a matriz de comunicação entre serviços e o mapeamento de esquemas físicos de banco de dados (Flyway/JPA).

---

## 1. Diagrama C4 de Containers & Infraestrutura

```mermaid
graph TD

%% --- Estilos de Nós ---
    classDef client fill:#f5f6fa,stroke:#2f3640,stroke-width:2px,color:#2f3640;
    classDef gateway fill:#8c7ae6,stroke:#fff,stroke-width:2px,color:#fff;
    classDef authService fill:#00a8ff,stroke:#fff,stroke-width:2px,color:#fff;
    classDef coreService fill:#4cd137,stroke:#fff,stroke-width:2px,color:#fff;
    classDef mailService fill:#e1b12c,stroke:#fff,stroke-width:2px,color:#fff;
    classDef infra fill:#353b48,stroke:#fff,stroke-width:2px,color:#fff;
    classDef database fill:#fbc531,stroke:#2f3640,stroke-width:2px,color:#2f3640;
    classDef broker fill:#e84118,stroke:#fff,stroke-width:2px,color:#fff;

%% --- Camada de Clientes ---
    subgraph Clients [Camada de Clientes]
        Browser[SPA / React Front-end]:::client
        PDV[Terminal de PDV / Checkout]:::client
    end

%% --- Edge / Borda ---
    subgraph EdgeLayer [Borda & Segurança de Borda]
        Gateway[API Gateway :8080]:::gateway
        Redis[(Redis: Rate Limiter & Token Bucket)]:::infra
    end

%% --- Cluster de Microsserviços ---
    subgraph Services [Serviços da Aplicação]
        Auth[Auth Service :8081 - OAuth2 / OIDC]:::authService
        Inv[Inv Service :8083 - Monólito Modular]:::coreService
        Mail[Mail Service :8082 - Consumidor Assíncrono]:::mailService
    end

%% --- Persistência e Mensageria ---
    subgraph PersistenceLayer [Persistência e Mensageria]
        AuthDB[(PostgreSQL: Auth DB)]:::database
        InvDB[(PostgreSQL: Core & Ledger DB)]:::database
        RabbitMQ[RabbitMQ: Topic Exchanges & DLQs]:::broker
    end

%% --- Conexões Síncronas (HTTP) ---
    Browser -->|HTTP REST| Gateway
    PDV -->|HTTP REST / Long Polling| Gateway
    Gateway -->|Rate Limit Check| Redis
    Gateway -->|Proxy /oauth2, /usuarios| Auth
    Gateway -->|Proxy /vendas, /produtos, /financeiro| Inv

    Auth -->|JDBC / JPA| AuthDB
    Inv -->|JDBC / JPA / Raw SQL| InvDB

%% --- Conexões Assíncronas (AMQP) ---
    Auth -.->|Publica: auth.v1.password-reset| RabbitMQ
    Inv -.->|Publica via Outbox: vendas.exchange| RabbitMQ
    Inv -.->|Publica via Outbox: estoque.exchange| RabbitMQ

    RabbitMQ -.->|Consome com Idempotência| Mail
    RabbitMQ -.->|DLQ Retries| RabbitMQ
```

---

## 2. Matriz de Comunicação entre Componentes

| Origem | Destino | Protocolo | Padrão / Semântica | Descrição |
| :--- | :--- | :--- | :--- | :--- |
| **Cliente / PDV** | **API Gateway** | HTTP/1.1 (JSON) | Síncrono / Long Polling | Requisições públicas, autenticação e espera não-bloqueante via `DeferredResult`. |
| **API Gateway** | **Auth Service** | HTTP/1.1 | Proxy Reverso / Stateless | Roteamento de rotas `/oauth2/**` e `/usuarios/**`. |
| **API Gateway** | **Inv Service** | HTTP/1.1 | Proxy Reverso / Stateless | Roteamento de rotas de catálogo, checkout e dashboards financeiros. |
| **Inv Service (Checkout)** | **Inv Service (Finance)** | Em Memória | Spring Events (`VendaPagaEvent`) | Publicação de `VendaPagaEvent` (`inv.shared.event`) consumida por `VendaPagaEventListener`. |
| **Inv Service (Worker)** | **RabbitMQ** | AMQP 0-9-1 | Polling Transacional (`SKIP LOCKED`) | Worker de Outbox lendo tabela `outbox` e postando em topic exchanges. |
| **Auth Service** | **RabbitMQ** | AMQP 0-9-1 | Push Assíncrono | Publicação direta de eventos de recuperação de senha (`PasswordResetMessage`). |
| **RabbitMQ** | **Mail Service** | AMQP 0-9-1 | Idempotent Consumer | Consumo de e-mails transacionais com deduplicação em `processed_events`. |

---

## 3. Mapeamento Físico de Banco de Dados & Migrations (Flyway)

A integridade do modelo de dados é gerenciada de forma estrita via scripts de migração do Flyway e tabelas de suporte do ecossistema Spring.

```mermaid
classDiagram
    direction TB

    class Produtos {
        +bigint id PK
        +varchar codigo_barras UK
        +numeric estoque_disponivel
        +numeric estoque_reservado
        +numeric preco_venda
        +bigint version
    }

    class Vendas {
        +bigint id PK
        +timestamp data_hora
        +numeric valor_total
        +varchar status
        +bigint version
    }

    class ItensVenda {
        +bigint id PK
        +bigint venda_id FK
        +bigint produto_id FK
        +numeric quantidade
        +numeric preco_unitario_snapshot
    }

    class LancamentosFinanceiros {
        +bigint id PK
        +bigint venda_id FK
        +uuid referencia UK
        +varchar tipo
        +numeric valor
        +timestamp data_hora
    }

    class Outbox {
        +bigint id PK
        +varchar exchange
        +varchar routing_key
        +text payload
        +timestamp created_at
    }

    class FechamentoCaixaDiario {
        +date data_referencia PK
        +numeric saldo_inicial
        +numeric total_creditos
        +numeric total_debitos
        +numeric saldo_final
        +bigint ultimo_lancamento_id
    }

    Vendas "1" -- "*" ItensVenda : possui
    Produtos "1" -- "*" ItensVenda : referencia
    Vendas "1" -- "*" LancamentosFinanceiros : gera
```

### A. Esquema do `inv-service` ([`V1__Schema_Inicial.sql`](../../apps/inv-service/src/main/resources/db/migration/V1__Schema_Inicial.sql))

| Tabela | Bounded Context | Finalidade Arquitetural |
| :--- | :--- | :--- |
| `produtos` | **Inventory** | Registro de catálogo e saldo atômico (`estoque_disponivel`, `estoque_reservado`). |
| `movimentacoes_estoque` | **Inventory** | Trilha histórica de auditoria de entradas, saídas e reservas. |
| `vendas` | **Checkout** | Agregado raiz de transação comercial e máquina de estados (`ABERTA`, `AGUARDANDO_PAGAMENTO`, `CONCLUIDA`, `CANCELADA`). |
| `itens_venda` | **Checkout** | Snapshots imutáveis de preço e produto no momento da compra. |
| `outbox` | **Shared** | Fila física de transações distribuídas para despacho AMQP sem 2PC. |
| `lancamentos_financeiros` | **Finance** | **Ledger Contábil Append-Only**; registros imutáveis com `referencia` UUID única. |
| `financial_projection_venda` | **Finance** | Tabela de leitura CQRS contendo saldos consolidados por venda. |
| `financial_projection_checkpoint` | **Finance** | Tabela de deduplicação e garantia *exactly-once* do pipeline CQRS. |
| `financial_reconciliation_checkpoint`| **Finance** | Marcação de *High Watermark* para reconciliação incremental. |
| `projection_retry_queue` | **Finance** | Fila de auto-cura para reprocessar falhas assíncronas com `SKIP LOCKED`. |
| `fechamento_caixa_diario` | **Finance** | Tabela de snapshots consolidados D+0 de fechamento diário. |

### B. Esquema do `auth-service` ([`V2__create_oauth_tables.sql`](../../apps/auth-service/src/main/resources/db/migration/V2__create_oauth_tables.sql))

| Tabela | Finalidade Arquitetural |
| :--- | :--- |
| `oauth2_registered_client` | Registro persistido de aplicações clientes (Front-end SPA, PDV Desktop, Mobile). |
| `oauth2_authorization` | Estado das sessões OAuth2, códigos de autorização emitidos e Refresh Tokens ativos. |
| `oauth2_authorization_consent` | Consentimentos de escopo concedidos pelos usuários. |
| `usuarios` | Base cadastral de credenciais com senhas criptografadas em BCrypt. |

### C. Esquema do `mail-service` (JPA Automático)

> [!NOTE]
> **Estratégia Mista de Migrações:** Enquanto o `inv-service` e o `auth-service` utilizam **Flyway** devido à criticidade de versionamento de regras de negócio, tabelas contábeis e especificações padrão OIDC, o `mail-service` utiliza gerenciamento automático de schema via **Hibernate (`ddl-auto=update`)**. Como o consumidor de e-mails atua como um worker assíncrono cuja única persistência é a tabela efêmera de deduplicação `processed_events` (sem constraints relacionais complexas), a criação direta via JPA reduz o overhead de manutenção de scripts manuais.

| Tabela | Finalidade Arquitetural |
| :--- | :--- |
| `processed_events` | Tabela de idempotência de eventos consumidos (`event_id` como PK) para proteção contra envio duplicado de e-mails. |

---

## 4. Rastreabilidade e Observabilidade Distribuída

* **Distributed Tracing:** Toda mensagem originada no sistema carrega um cabeçalho `eventId` / `correlationId` para rastreamento ponta a ponta no Grafana Loki.
* **Métricas em Tempo Real:** Endpoints `/actuator/prometheus` expostos em todas as aplicações com métricas customizadas de locks e execução de jobs.
* **Logs Não-Bloqueantes:** Utilização de `Logstash Async Appender` para evitar overhead de I/O de disco em threads HTTP de requisição.
