# 🛒 Bounded Context: Checkout (Vendas & Pagamentos)

O módulo **Checkout** é responsável pela orquestração do ciclo de vida das vendas, integração com gateways de pagamento, controle de concorrência na liquidação e notificação em tempo real para os caixas de PDV via **Long Polling Não-Bloqueante**.

---

## 1. Glossário da Linguagem Ubíqua (Ubiquitous Language)

| Termo | Definição no Domínio |
| :--- | :--- |
| **Venda** | Agregado raiz que representa o pedido comercial, contendo o valor total, status, versionamento otimista e a lista de itens comprados. |
| **Item de Venda** | Snapshot imutável do produto (preço unitário, quantidade e nome) no momento exato em que foi adicionado ao carrinho. |
| **Intenção de Pagamento** | Registro prévio de uma tentativa de pagamento associada a uma referência UUID externa e método de pagamento (Pix, Cartão, Dinheiro). |
| **Recibo** | Projeção formatada de uma venda ativa ou concluída, contendo a lista de itens, subtotais e dados de abertura. |
| **Long Polling (`DeferredResult`)** | Mecanismo de espera reativa assíncrona onde o terminal de PDV aguarda até 30 segundos por uma mudança de estado da venda sem prender threads do servidor. |
| **Venda Expirada** | Venda que permaneceu em `AGUARDANDO_PAGAMENTO` além do tempo limite configurado e tem seus itens devolvidos automaticamente ao estoque disponível por um job agendado. |

---

## 2. Máquinas de Estados do Domínio

### A. Ciclo de Vida da Venda (`StatusVenda`)

```mermaid
stateDiagram-v2
    [*] --> ABERTA: POST /vendas (Reserva Atômica no Estoque)
    ABERTA --> AGUARDANDO_PAGAMENTO: POST /pagamentos/intencao
    
    AGUARDANDO_PAGAMENTO --> CONCLUIDA: Webhook Confirmado (Baixa Definitiva + Outbox + VendaPagaEvent)
    AGUARDANDO_PAGAMENTO --> CANCELADA: VendaExpiracaoJob / Timeout (Estorno da Reserva de Estoque)
    
    CONCLUIDA --> [*]
    CANCELADA --> [*]
```

### B. Ciclo de Vida do Pagamento (`StatusPagamento`)

```mermaid
stateDiagram-v2
    [*] --> CRIADO: Criar Intenção
    CRIADO --> PROCESSANDO: Método exige processamento externo
    PROCESSANDO --> CONFIRMADO: Webhook Sucesso (/pagamentos/webhook/confirmar)
    PROCESSANDO --> FALHADO: Webhook Erro (/pagamentos/webhook/falhar)
    
    CONFIRMADO --> [*]
    FALHADO --> [*]
```

---

## 3. Diagramas de Sequência e Fluxos do Domínio

### Fluxo Completo: Abertura, Long Polling de PDV, Webhook e Conclusão

```mermaid
sequenceDiagram
    autonumber
    actor Operador as Operador de PDV
    participant PDV as Terminal PDV (Front-end)
    participant VendaCtrl as VendaController
    participant VendaSvc as VendaService
    participant Registry as VendaStatusRegistry
    participant GatewayExt as Gateway Pagamentos (Externo)
    participant PagtoCtrl as PagamentoController
    participant Outbox as Tabela Outbox (DB)
    participant Finance as Finance (VendaPagaEventListener)

    Operador->>PDV: Escaneia produtos e finaliza carrinho
    PDV->>VendaCtrl: POST /vendas (itens, quantidades)
    VendaCtrl->>VendaSvc: iniciarVenda() (Reserva Atômica Estoque)
    VendaCtrl-->>PDV: Retorna Venda (ID: 100, Status: ABERTA)

    PDV->>VendaCtrl: GET /vendas/100/status?wait=true&sinceVersion=1
    Note over VendaCtrl,Registry: Thread HTTP liberada. Conexão estacionada no VendaStatusRegistry (30s)

    GatewayExt->>PagtoCtrl: POST /pagamentos/webhook/confirmar (pagamentoId, NSU)
    PagtoCtrl->>VendaSvc: processarPagamento(vendaId=100)
    Note over VendaSvc: Lock Pessimista (findByIdWithLock)
    VendaSvc->>VendaSvc: Baixa Definitiva no Estoque
    VendaSvc->>Outbox: INSERT outbox (VendaConcluidaEvent)
    VendaSvc->>Finance: Publica VendaPagaEvent (@EventListener síncrono na mesma TX)
    Finance->>Finance: INSERT INTO lancamentos_financeiros (Ledger)
    Note over VendaSvc,Finance: Commit da Transação Principal (Venda + Estoque + Ledger)
    
    VendaSvc-->>Registry: VendaAtualizadaEvent (@TransactionalEventListener AFTER_COMMIT)
    Registry-->>PDV: Resolve DeferredResult: Status CONCLUIDA (HTTP 200)
    PDV->>Operador: Imprime Recibo e libera mercadoria!
```

---

## 4. Rotinas Periódicas de Expiração (Housekeeping)

O componente [`VendaExpiracaoJob`](../../apps/inv-service/src/main/java/inv/checkout/infrastructure/jobs/VendaExpiracaoJob.java) roda periodicamente a cada 5 minutos:
1. Localiza vendas em `StatusVenda.AGUARDANDO_PAGAMENTO` criadas há mais de 30 minutos.
2. Executa `vendaService.cancelarVendaExpirada(vendaId)` sob lock pessimista.
3. Invoca `estoqueService.estornarReservaEstoque()`, devolvendo as unidades reservadas para `estoque_disponivel` sem intervenção manual.

---

## 5. ADRs Vinculados a Este Domínio

* 📑 **[ADR-0002: Controle Híbrido de Concorrência e Estoque Atômico](../adr/0002-atomic-sql-inventory-and-hybrid-locking.md)**
* 📑 **[ADR-0003: Long Polling Não-Bloqueante com DeferredResult](../adr/0003-async-long-polling-pos-deferred-result.md)**
* 📑 **[ADR-0004: Transactional Outbox Pattern com SKIP LOCKED](../adr/0004-transactional-outbox-with-skip-locked.md)**
