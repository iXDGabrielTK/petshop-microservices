# 💰 Bounded Context: Finance (Motor Contábil & Financeiro)

O módulo **Finance** é o núcleo contábil do sistema. Ele é projetado sob os princípios de **Imutabilidade (Ledger Append-Only)**, **Deduplicação Idempotente**, **CQRS Assíncrono com Auto-Cura** e **Consolidação Incremental via High Watermark**.

---

## 1. Glossário da Linguagem Ubíqua (Ubiquitous Language)

| Termo | Definição no Domínio |
| :--- | :--- |
| **Lançamento Financeiro** | Registro atômico e imutável de uma movimentação monetária vinculada a uma venda. Não sofre edição ou exclusão. |
| **Ledger (Livro-Razão)** | Conjunto sequencial e histórico de todos os lançamentos financeiros do sistema (`lancamentos_financeiros`). |
| **Referência UUID** | Identificador único universal gerado na intenção de pagamento, utilizado como chave de idempotência física. |
| **Projeção CQRS** | Visão pré-calculada e desnormalizada do saldo de uma venda (`financial_projection_venda`), otimizada para leitura instantânea $O(1)$. |
| **Checkpoint CQRS** | Tabela de controle (`financial_projection_checkpoint`) que garante que um lançamento específico seja projetado exatamente uma vez (*exactly-once*). |
| **High Watermark** | Identificador máximo (`id`) de lançamento financeiro processado até determinado ponto no tempo, usado como marco delimitador. |
| **Fechamento D+0** | Snapshot consolidado diário contendo saldo inicial, créditos, débitos e saldo final até determinado High Watermark. |
| **Reconciliação $O(\Delta)$** | Auditoria incremental que compara o saldo real no Ledger com o saldo projetado no CQRS apenas para as vendas modificadas no período. |

---

## 2. Diagramas de Sequência e Fluxos do Domínio

### A. Fluxo de Registro de Lançamento e Projeção CQRS Assíncrona

```mermaid
sequenceDiagram
    autonumber
    participant Checkout as Checkout (VendaEventHandler)
    participant VendaPagaListener as VendaPagaEventListener (Finance)
    participant LedgerDB as PostgreSQL (Ledger)
    participant Listener as FinancialProjectionListener
    participant ProjDB as PostgreSQL (Projeção/Checkpoint)
    participant RetryQueue as Fila de Retry (DB)

    Checkout->>VendaPagaListener: Publica VendaPagaEvent (@EventListener síncrono na mesma TX)
    VendaPagaListener->>LedgerDB: INSERT INTO lancamentos_financeiros (referencia UUID, valor, tipo)
    Note over Checkout,LedgerDB: Commit da Transação Principal (Venda + Estoque + Ledger)
    
    VendaPagaListener-->>Listener: Evento LancamentoRegistradoEvent (@TransactionalEventListener AFTER_COMMIT)
    
    rect rgb(235, 248, 255)
        Note over Listener,ProjDB: Execução Assíncrona (Pool: projectionExecutor)
        Listener->>ProjDB: INSERT INTO financial_projection_checkpoint (lancamento_id)
        alt Inserção no Checkpoint com Sucesso (Primeira vez)
            Listener->>ProjDB: UPSERT INTO financial_projection_venda (saldo, creditos, estornos)
        else DuplicateKeyException (Evento já processado)
            Listener->>Listener: Ignora silenciosamente (Idempotência garantida)
        else Falha Transitória / Erro de Banco
            Listener->>RetryQueue: INSERT INTO projection_retry_queue (payload JSON, status 'PENDENTE')
        end
    end
```

### B. Ciclo de Fechamento Diário D+0 e Reconciliação Incremental

```mermaid
sequenceDiagram
    autonumber
    participant Scheduler as FechamentoCaixaScheduler (00:01 AM)
    participant FechamentoService as FechamentoCaixaService
    participant LedgerRepo as LancamentoFinanceiroRepository
    participant ReconService as ReconciliacaoService
    participant DB as PostgreSQL

    Scheduler->>FechamentoService: executarFechamentoTransacional()
    FechamentoService->>DB: Obter último FechamentoCaixaDiario (ultimoLancamentoId)
    FechamentoService->>LedgerRepo: calcularSnapshotAtomico(ultimoLancamentoId)
    Note over LedgerRepo,DB: CTE com High Watermark (MAX id)
    LedgerRepo-->>FechamentoService: SnapshotResult (creditos, debitos, novoMaxId)
    
    FechamentoService->>DB: INSERT INTO fechamento_caixa_diario
    
    rect rgb(255, 250, 235)
        Note over FechamentoService,ReconService: Reconciliação em Nova Transação (REQUIRES_NEW)
        FechamentoService->>ReconService: executarReconciliacaoIncremental(ultimoLancamentoId, novoMaxId)
        ReconService->>DB: auditarDivergencias(ultimoLancamentoId, novoMaxId)
        alt Divergência Detectada
            ReconService->>ReconService: Classifica severidade (WARN, ALERT, CRITICAL)
        end
        ReconService->>DB: INSERT INTO financial_reconciliation_checkpoint (novoMaxId)
    end
```

---

## 3. Modelo Físico de Tabelas do Domínio Financeiro

```mermaid
erDiagram
    LANCAMENTOS_FINANCEIROS ||--o| FINANCIAL_PROJECTION_CHECKPOINT : deduplica
    VENDAS ||--o{ LANCAMENTOS_FINANCEIROS : gera
    VENDAS ||--o| FINANCIAL_PROJECTION_VENDA : consolida
    
    LANCAMENTOS_FINANCEIROS {
        bigint id PK
        bigint venda_id FK
        uuid referencia UK "Chave de Idempotência"
        varchar tipo "CREDITO, DEBITO, ESTORNO"
        numeric valor "Valor com sinal"
        timestamp data_hora
    }

    FINANCIAL_PROJECTION_VENDA {
        bigint venda_id PK
        numeric saldo
        numeric total_creditos
        numeric total_estornos
        timestamp ultima_atualizacao
    }

    FINANCIAL_PROJECTION_CHECKPOINT {
        bigint lancamento_id PK
        timestamp processado_em
        varchar status
    }

    PROJECTION_RETRY_QUEUE {
        bigint id PK
        bigint lancamento_id UK
        jsonb payload
        text erro
        int tentativas
        varchar status
        timestamp proxima_execucao
    }

    FECHAMENTO_CAIXA_DIARIO {
        date data_referencia PK
        numeric saldo_inicial
        numeric total_creditos
        numeric total_debitos
        numeric saldo_final
        bigint ultimo_lancamento_id "High Watermark"
        timestamp processado_em
    }
```

---

## 4. Matriz de Resiliência e Tratamento de Erros

| Cenário de Falha | Mecanismo de Defesa | Efeito no Sistema |
| :--- | :--- | :--- |
| **Reenvio de Webhook Duplicado** | `UK (referencia)` no Ledger | Rejeição atômica no banco sem duplicar lançamento. |
| **Crash do servidor antes da projeção CQRS** | Evento não executado | O Ledger permanece intacto; a reconciliação noturna sinaliza divergência ou a fila de retry reprocessa. |
| **Lock temporário durante Upsert CQRS** | `catch (Exception)` no Listener | Payload serializado na `projection_retry_queue` e reprocessado pelo `ProjectionRetryScheduler`. |
| **Divergência de Saldo Real vs Projetado** | `ReconciliacaoService` $O(\Delta)$ | Alerta classificado automaticamente: \(\le 0,01\) (Warn), \(\le 1,00\) (Alert), \(> 1,00\) (Critical). |

---

## 5. ADRs Vinculados a Este Domínio

* 📑 **[ADR-0005: Ledger Financeiro Imutável (Append-Only) com UUID](../adr/0005-append-only-financial-ledger-and-idempotency.md)**
* 📑 **[ADR-0006: Projeções CQRS Assíncronas e Auto-Cura via SKIP LOCKED](../adr/0006-cqrs-asynchronous-projections-and-self-healing-retry.md)**
* 📑 **[ADR-0007: Fechamento de Caixa D+0 com High Watermark](../adr/0007-d0-daily-cash-closing-and-high-watermark.md)**
