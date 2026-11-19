# 🛒 Bounded Context: Checkout (Vendas & Pagamentos)

O módulo **Checkout** é responsável pela orquestração do ciclo de vida das vendas, integração com gateways de pagamento, controle de concorrência na liquidação e notificação em tempo real para os caixas de PDV via **Long Polling Não-Bloqueante**.

---

## 1. Glossário da Linguagem Ubíqua (Ubiquitous Language)

| Termo | Definição no Domínio |
| :--- | :--- |
| **Venda** | Agregado raiz que representa o pedido comercial, contendo o valor total, status, versionamento otimista e a lista de itens comprados. |
| **Item de Venda** | Snapshot imutável do produto (preço unitário, quantidade e nome) no momento exato em que foi adicionado ao carrinho, armazenando apenas a referência escalar `Long produtoId`. |
| **Intenção de Pagamento** | Registro prévio de uma tentativa de pagamento associada a uma referência UUID externa e método de pagamento (Pix, Cartão, Dinheiro). |
| **Recibo** | Projeção formatada de uma venda ativa ou concluída, contendo a lista de itens, subtotais e dados de abertura. |
| **Long Polling (`DeferredResult`)** | Mecanismo de espera reativa assíncrona onde o terminal de PDV aguarda até 30 segundos por uma mudança de estado da venda sem prender threads do servidor. |
| **Venda Expirada** | Venda que permaneceu em `ABERTA` ou `AGUARDANDO_PAGAMENTO` além do tempo limite configurado e tem seus itens devolvidos automaticamente ao estoque disponível por um job agendado. |

---

## 2. Máquinas de Estados do Domínio

### A. Ciclo de Vida da Venda (`StatusVenda`)

```mermaid
stateDiagram-v2
    [*] --> ABERTA: POST /vendas (Reserva Atômica no Estoque)
    ABERTA --> AGUARDANDO_PAGAMENTO: POST /pagamentos/intencao
    
    AGUARDANDO_PAGAMENTO --> CONCLUIDA: Webhook Confirmado (Baixa Definitiva via EstoqueCommandService + VendaPagaEvent)
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
    participant EstoqueAPI as EstoqueCommandService (inv.inventory.api)
    participant Registry as VendaStatusRegistry
    participant GatewayExt as Gateway Pagamentos (Externo)
    participant PagtoCtrl as PagamentoController
    participant PagtoSvc as PagamentoService
    participant RabbitMQ as RabbitMQ (pagamento.confirmado.queue)
    participant Handler as VendaEventHandler
    participant Finance as Finance (VendaPagaEventListener)

    Operador->>PDV: Escaneia produtos e finaliza carrinho
    PDV->>VendaCtrl: POST /vendas (itens, quantidades)
    VendaCtrl->>VendaSvc: iniciarVenda()
    VendaSvc->>EstoqueAPI: reservar(ComandoEstoqueVenda) [MANDATORY, produtoId ASC]
    VendaCtrl-->>PDV: Retorna Venda (ID: 100, Status: AGUARDANDO_PAGAMENTO)

    PDV->>VendaCtrl: GET /vendas/100/status?wait=true&sinceVersion=1
    Note over VendaCtrl,Registry: Thread HTTP liberada. Conexão estacionada no VendaStatusRegistry (30s)

    GatewayExt->>PagtoCtrl: POST /pagamentos/webhook/confirmar (pagamentoId, NSU)
    PagtoCtrl->>PagtoSvc: confirmarPagamentoWebhook()
    PagtoSvc->>RabbitMQ: Publica PagamentoConfirmadoEvent (pagamentos.exchange)
    RabbitMQ->>Handler: Consome PagamentoConfirmadoEvent (pagamento.confirmado.queue)
    Note over Handler: Lock Pessimista na Venda (findByIdWithLock)
    Handler->>Handler: Registra pagamento na Venda e conclui a Venda
    Handler->>EstoqueAPI: confirmarBaixa(ComandoEstoqueVenda) [MANDATORY, produtoId ASC]
    Handler->>Finance: Publica VendaPagaEvent (@EventListener síncrono na mesma TX)
    Finance->>Finance: INSERT INTO lancamentos_financeiros (Ledger)
    Note over Handler,Finance: Commit da Transação Principal (Venda + Estoque + Ledger)
    
    Handler-->>Registry: VendaAtualizadaEvent (@TransactionalEventListener AFTER_COMMIT)
    Registry-->>PDV: Resolve DeferredResult: Status CONCLUIDA (HTTP 200)
    PDV->>Operador: Imprime Recibo e libera mercadoria!
```

---

## 4. Rotinas Periódicas de Expiração (Housekeeping)

O componente [`VendaExpiracaoJob`](../../apps/inv-service/src/main/java/inv/checkout/infrastructure/jobs/VendaExpiracaoJob.java) roda periodicamente a cada 5 minutos:
1. Localiza vendas em `StatusVenda.ABERTA` ou `StatusVenda.AGUARDANDO_PAGAMENTO` criadas há mais de 30 minutos.
2. Executa `vendaService.cancelarVendaExpirada(vendaId)` sob lock pessimista (`findByIdWithLock`), aplicando um **guard pós-lock idempotente** que retorna silenciosamente caso a venda já esteja `CANCELADA` (Q10/F2).
3. Invoca `estoqueCommandService.estornarReserva(comandoPara(venda))` (`Propagation.MANDATORY`, lote ordenado por `produtoId ASC`), devolvendo as unidades reservadas para `estoque_disponivel` sem intervenção manual.

---

## 5. Especificação do Contrato de Webhooks de Pagamento

Os webhooks de liquidação externa comunicam-se diretamente com o [`PagamentoController`](../../apps/inv-service/src/main/java/inv/checkout/infrastructure/web/controller/PagamentoController.java) para confirmar ou rejeitar transações de cartão e Pix:

* **Endpoint Principal:** `POST /pagamentos/webhook/confirmar/{pagamentoId}`
* **Endpoint de Falha:** `POST /pagamentos/webhook/falhar/{pagamentoId}`
* **Precedência e Isenção no API Gateway:** A rota dedicada `inv-webhook` (`order: 1`) intercepta o padrão `/pagamentos/webhook/**` antes da rota corporativa geral de estoque (`order: 2`). Esta rota é **isenta de `RequestRateLimiter`**, garantindo que notificações legítimas e rajadas de confirmação emitidas pelas adquirentes não sofram descarte (HTTP 429).
* **Contrato de Validação DTO ([`ConfirmarPagamentoWebhookRequest`](../../apps/inv-service/src/main/java/inv/checkout/infrastructure/web/dto/ConfirmarPagamentoWebhookRequest.java)):**
  ```json
  {
    "valor_recebido": 150.50,
    "nsu": "NSU-987654321"
  }
  ```
* **Regras de Integridade do Payload:**
  - `valor_recebido`: `@NotNull(message = "valor_recebido é obrigatório")`, `@Positive(message = "valor_recebido deve ser positivo")`
  - `nsu`: `@NotBlank(message = "nsu é obrigatório")`
* **Tratamento Semântico de Erro (RFC 7807):**
  Payloads sem corpo, valores negativos, nulos ou strings de NSU vazias/em branco disparam validação do Bean Validation interceptada pelo `GlobalExceptionHandler`, respondendo com **HTTP 400 Bad Request**:
  ```json
  {
    "type": "https://petshop.com/errors/invalid-data",
    "title": "Dados Inválidos",
    "status": 400,
    "detail": "Erro de validação nos campos",
    "fields": {
      "valor_recebido": "valor_recebido deve ser positivo",
      "nsu": "nsu é obrigatório"
    },
    "timestamp": "2026-10-30T21:00:00Z"
  }
  ```
* **Concorrência e Idempotência:**
  A recepção do webhook dispara `pagamentoService.confirmarPagamentoWebhook(pagamentoId, valor, nsu)` e publica `PagamentoConfirmadoEvent` via RabbitMQ. O consumidor do evento adquire lock pessimista (`findByIdWithLock`) na venda correspondente, aplica a liquidação, confirma a baixa definitiva no estoque (`EstoqueCommandService.confirmarBaixa`) e registra a transação imutável no Ledger contábil.

---

## 6. Contrato de Criação de Intenção de Pagamento e Idempotência

O registro de intenções de pagamento ocorre via `POST /pagamentos/intencao/{vendaId}` no [`PagamentoController`](../../apps/inv-service/src/main/java/inv/checkout/infrastructure/web/controller/PagamentoController.java), orquestrado pelo [`PagamentoService`](../../apps/inv-service/src/main/java/inv/checkout/usecase/PagamentoService.java) e [`PagamentoRepository`](../../apps/inv-service/src/main/java/inv/checkout/infrastructure/persistence/PagamentoRepository.java).

* **Validação Decimal Sem Arredondamento (`CriarIntencaoPagamentoRequest` e Service):**
  - Campos obrigatórios (`@NotNull`): `uuidReferencia` (`UUID`), `metodo` (`MetodoPagamento`), `valor` (`BigDecimal`).
  - Regras numéricas no DTO: `@Positive` e `@Digits(integer = 8, fraction = 2)` alinhadas à coluna `valor_esperado NUMERIC(10, 2)`.
  - Normalização sem arredondamento: o construtor compacto de [`CriarIntencaoPagamentoRequest`](../../apps/inv-service/src/main/java/inv/checkout/infrastructure/web/dto/CriarIntencaoPagamentoRequest.java) remove zeros à direita com `stripTrailingZeros()` (ajustando escalas negativas via `setScale(0)`), aceitando representações equivalentes como `10.000`, `10.00` e `100` e rejeitando com **HTTP 400** valores com mais de 2 casas decimais significativas (ex.: `10.005`) para evitar arredondamentos silenciosos no banco e falsos conflitos em retries.
  - Defesa em profundidade no `PagamentoService`: rejeita com `BusinessException` (`400`) quando `valor == null`, `valor.signum() <= 0`, `valor > 99999999.99` ou `valor.stripTrailingZeros().scale() > 2`.

* **Protocolo de Idempotência em 6 Etapas (`criarIntencaoPagamento`):**
  1. **Consulta rápida:** busca `findByUuidReferencia(uuidReferencia)`. Se existir, valida igualdade de `vendaId`, `metodo` e `valorEsperado.compareTo(valor) == 0`; se coincidir, retorna o `pagamentoId` (`200`), mesmo que a venda já esteja `CONCLUIDA` ou `CANCELADA`; se divergir, lança `ConflictException` (`409`).
  2. **Lock pessimista na venda:** executa `VendaRepository.findByIdWithLock(vendaId)` (`SELECT ... FOR UPDATE`), lançando `ResourceNotFoundException` (`404`) caso a venda não exista e serializando com conclusão, expiração e cancelamento.
  3. **Reconsulta pós-lock:** executa novamente `findByUuidReferencia(uuidReferencia)` antes de validar o status da venda, preservando retries legítimos de transações concorrentes mesmo que a venda tenha mudado para `CONCLUIDA` ou `CANCELADA` durante a espera pelo lock (ou lançando `409` se o payload divergir).
  4. **Validação de status da venda:** verifica `venda.getStatus().aceitaPagamento()`; caso contrário, lança `ConflictException` (`409`).
  5. **Inserção atômica com fonte única do domínio:** instancia `Pagamento` (aplicando `processar()` quando `metodo.isExigeProcessamento()` for `true`) e persiste via query nativa `inserirIntencaoIdempotente` (`INSERT INTO pagamentos (...) VALUES (...) ON CONFLICT (uuid_referencia) DO NOTHING RETURNING id`). Se retornar o ID gerado, devolve-o (`200`).
  6. **Fallback em conflito concorrente:** se o `Optional<Long>` retornar vazio em razão da constraint única `uk_pagamento_uuid` (ex.: concorrência entre vendas distintas com o mesmo UUID), consulta `findByUuidReferencia(uuidReferencia)` novamente e aplica a comparação compartilhada (`200` se igual; `ConflictException` `409` revertendo a transação se divergente, sem sobrescrever o registro existente).

* **Semântica de Erros (`ProblemDetail` RFC 7807 via `GlobalExceptionHandler`):**
  - **HTTP 400 Bad Request:** falha de validação DTO (`invalid-data`) ou regra de valor no service (`BusinessException` / `business-rule-violation`).
  - **HTTP 404 Not Found:** `vendaId` inexistente (`ResourceNotFoundException` / `resource-not-found`).
  - **HTTP 409 Conflict:** venda em status que não aceita pagamento ou `uuidReferencia` reutilizado com `vendaId`, `metodo` ou `valor` divergentes (`ConflictException`, `title: "Conflito de Estado"`, `type: "https://petshop.com/errors/conflict"`).

---

## 7. ADRs Vinculados a Este Domínio

* 📑 **[ADR-0002: Controle Híbrido de Concorrência e Estoque Atômico](../adr/0002-atomic-sql-inventory-and-hybrid-locking.md)**
* 📑 **[ADR-0003: Long Polling Não-Bloqueante com DeferredResult](../adr/0003-async-long-polling-pos-deferred-result.md)**
* 📑 **[ADR-0004: Transactional Outbox Pattern com SKIP LOCKED](../adr/0004-transactional-outbox-with-skip-locked.md)**
* 📑 **[ADR-0011: Open Host Service Síncrono de Comandos e Catálogo (Checkout → Inventory)](../adr/0011-synchronous-open-host-service-checkout-inventory.md)**
