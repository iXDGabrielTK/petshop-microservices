# ADR-0012: Pipeline Idempotente de Pagamento e Cancelamento em Fase Única de Venda Concluída

* **Status:** Aceito
* **Data:** 2026-11-04
* **Autores:** Equipe de Engenharia
* **Bounded Contexts Afetados:** Checkout, Finance, Inventory, Shared
* **Serviços:** `apps/inv-service`

---

## 1. Contexto e Problema (Context & Problem Statement)

Durante as análises de arquitetura e concorrência formalizadas no [DEBT-ARCH-002](../debt/DEBT-ARCH-002/README.md) e consolidadas no [DEBT-ARCH-003](../debt/DEBT-ARCH-003/README.md), foram identificadas vulnerabilidades estruturais no pipeline de pagamento e a ausência de um mecanismo consistente para cancelamento de vendas concluídas:

1. **Vulnerabilidade de Idempotência no Ledger Financeiro (F1):** `VendaPagaEventListener` persistia lançamentos via `save` (`GenerationType.IDENTITY`). Em reentregas concorrentes ou retentativas, a colisão na constraint `uk_lancamento_referencia` marcava a transação JPA como `rollback-only`, resultando em `UnexpectedRollbackException` ou `DataIntegrityViolationException` e descarte com erro no processamento de eventos.
2. **Ausência de Lock Pessimista e Falha em Reentregas no Checkout (F2):** `VendaEventHandler` realizava `findById` sem lock pessimista. Reentregas de mensagens para vendas já faturadas (`CONCLUIDA`) lançavam `BusinessException`, gerando rejeições indevidas para a Dead Letter Queue (DLQ).
3. **Emissão Falsa de Eventos no Webhook (F3):** `PagamentoService.confirmarPagamentoWebhook` publicava `PagamentoConfirmadoEvent` no Transactional Outbox mesmo quando `Pagamento.confirmar` era um no-op (chamadas duplicadas do adquirente).
4. **Acúmulo Duplicado em `@OneToMany` (F4):** Sob o relacionamento bidirecional entre `Venda` e `Pagamento`, instâncias persistidas já integravam a coleção em memória da sessão JPA. A chamada a `venda.registrarPagamentoConfirmado(pagamento)` duplicava referências na ausência de deduplicação idempotente segura.
5. **Falta de Operação Inversa no Open Host Service de Estoque (F7):** `EstoqueCommandService` possuía comandos para reserva, baixa e cancelamento de reserva, mas carecia de uma operação formal de devolução física de estoque (`devolverEstoque`) para recomposição pós-baixa.
6. **Inexistência de Cancelamento de Venda Concluída (F6):** O ciclo de vida da venda não contemplava a transição de `CONCLUIDA` para `CANCELADA`, impedindo estornos pós-faturamento com recompensa de estoque e conciliação contábil reversa.

---

## 2. Drivers de Decisão (Decision Drivers / Forces)

* **Consistência Transacional Forte (ACID) no Checkout e Inventário:** A alteração de status da venda, a recomposição física do estoque e o registro do evento de estorno no Outbox devem ocorrer sob uma única transação atômica local (`Propagation.MANDATORY`).
* **Consistência Eventual Segura no Finance:** A comunicação entre Checkout e Finance deve respeitar as fronteiras de Bounded Contexts (regra `R1` do ArchUnit), utilizando o Transactional Outbox e mensagens AMQP com deduplicação idempotente estrita.
* **Eliminação Estrutural de Deadlocks (`SQLState 40P01`):** Ordenação global determinística de locks (`vendas` primeiro via `SELECT ... FOR UPDATE`, seguido de `produtos` em ordem `produtoId ASC`).
* **Idempotência Nativa na Engine do PostgreSQL:** Prevenção de duplicatas contábeis e de estoque via instruções SQL atômicas (`ON CONFLICT DO NOTHING`) e índices únicos parciais.
* **Auditabilidade e Preservação do Modelo Append-Only:** Zero mutações ou exclusões na tabela `lancamentos_financeiros` ([ADR-0005](0005-append-only-financial-ledger-and-idempotency.md)).

---

## 3. Opções Consideradas (Considered Options)

1. **Opção 1: Verificação em Memória (`contains`) + `Propagation.REQUIRES_NEW` + Saga com `CANCELAMENTO_SOLICITADO`:**
   - Adicionar status intermediário `CANCELAMENTO_SOLICITADO`, orquestrando passos assíncronos entre serviços.
   - *Desvantagens:* Janela de inconsistência no PDV, colisão com o status `FATURADAS` da [ADR-0010](0010-dashboard-read-only-composition-module.md) e risco de descolamento de transações com `REQUIRES_NEW`.
2. **Opção 2: SQL Cruzado entre Contextos (Checkout escrevendo diretamente em `lancamentos_financeiros`):**
   - Transação única manipulando tabelas de múltiplos contextos diretamente.
   - *Desvantagens:* Violação grave de fronteiras de módulos (Regras `R1` e `R2` do ArchUnit), quebrando o encapsulamento do Monólito Modular.
3. **Opção 3 (Escolhida): 5 Camadas de Idempotência + Open Host Service `devolverEstoque` com Índice Parcial `V8` + Cancelamento em Fase Única `CONCLUIDA -> CANCELADA` com Outbox:**
   - Combinação de lock pessimista `findByIdWithLock`, flag `pagamentos.contabilizado` (`V7`), absorção nativa de duplicatas no Ledger via `ON CONFLICT DO NOTHING RETURNING id`, comando síncrono `devolverEstoque` ordenado por `produtoId ASC` com índice parcial `uk_movimentacoes_venda_produto_devolucao` (`V8`) e evento `VendaEstornadaEvent` no Outbox em fase única.

---

## 4. Decisão Tomada e Justificativa (Decision Outcome)

Adotamos a **Opção 3**, estruturada nos seguintes pilares técnicos:

### 4.1 As Cinco Camadas de Defesa no Pipeline de Pagamento (PR 1)
1. **Camada 1 (Ingresso / Webhook):** `Pagamento.confirmar` retorna `boolean` indicando se houve transição de estado real. O webhook apenas persiste o evento de confirmação no Outbox quando a transição é verdadeira (`F3`).
2. **Camada 2 (Lock Pessimista e Tabela de Decisão):** `VendaEventHandler` consome mensagens com `vendaRepository.findByIdWithLock(vendaId)`. Se o pagamento já estiver contabilizado, excedente ou estornado, responde com ACK idempotente imediatamente (`F2`). Se a venda já estiver `CONCLUIDA` ou `CANCELADA` recebendo pagamento tardio, o pagamento é marcado como `excedente = true`, o contador `pagamento.excedente.total` é incrementado em `+1.0` e o ACK é retornado sem reabrir a venda (`Q7`).
3. **Camada 3 (Deduplicação de Entidade em `Venda`):** `Venda.registrarPagamentoConfirmado` valida se o pagamento já consta na lista e implementa `Pagamento.class.hashCode()` baseado na PK / referência, garantindo igualdade segura no ciclo de vida JPA (`F4`).
4. **Camada 4 (Persistência com Flag `contabilizado` — Migration `V7`):** A coluna `pagamentos.contabilizado` registra se o valor do pagamento já foi assimilado nos saldos contábeis da venda (`valorPago` e `valorTroco`), impedindo dupla soma em reconexões.
5. **Camada 5 (Absorção Atômica no Ledger):** `LancamentoFinanceiroRepository.inserirIdempotente` utiliza:
   ```sql
   INSERT INTO lancamentos_financeiros (venda_id, referencia, tipo, valor)
   VALUES (:vendaId, :referencia, :tipo, :valor)
   ON CONFLICT (referencia) DO NOTHING
   RETURNING id;
   ```
   Caso a referência já exista, a query retorna vazio (`Optional.empty()`) sem marcar a transação como `rollback-only`. O evento `LancamentoRegistradoEvent` só é publicado quando o `id` é retornado (`F1`).

### 4.2 Open Host Service `devolverEstoque` e Índice Parcial `V8` (PR 2)
* O contrato público [`EstoqueCommandService`](../../apps/inv-service/src/main/java/inv/inventory/api/EstoqueCommandService.java) foi estendido com o método `void devolverEstoque(ComandoEstoqueVenda comando)` (`Propagation.MANDATORY`).
* **Consolidação em `TreeMap`:** Itens recebidos em lote são agregados por `produtoId` em um `TreeMap<Long, BigDecimal>`, garantindo ordenação natural estritamente crescente `produtoId ASC` para eliminação de deadlocks (`Q3`, `Q4`).
* **Sequência Fail-Fast e Índice Parcial `V8`:** Para cada produto consolidado, a movimentação `DEVOLUCAO` é persistida antes do `UPDATE` atômico de recomposição em `produtos`. O índice único parcial `uk_movimentacoes_venda_produto_devolucao` garante unicidade física de `(venda_id, produto_id)` para o tipo `DEVOLUCAO`:
   ```sql
   CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uk_movimentacoes_venda_produto_devolucao
       ON movimentacoes_estoque (venda_id, produto_id)
       WHERE tipo = 'DEVOLUCAO';
   ```
   Em caso de corrida sem lock de venda, a transação concorrente aborta imediatamente no índice com `SQLState 23505` sem adquirir row lock na tabela `produtos` (`Q5`).

### 4.3 Cancelamento Atômico em Fase Única e Outbox (PR 3)
* **Transição Atômica `CONCLUIDA -> CANCELADA`:** O cancelamento ocorre em fase única síncrona através de `vendaService.cancelarVendaConcluida(Long vendaId)`. Sob lock pessimista em `vendas`:
  1. Valida se a venda está em status `CONCLUIDA` (lança `CancelamentoVendaConflitoException` mapeada para HTTP 409 caso já cancelada ou pendente);
  2. Transiciona status para `CANCELADA`;
  3. Estorna exclusivamente pagamentos com `contabilizado = true && !excedente`;
  4. Invoca `estoqueCommandService.devolverEstoque` na mesma transação;
  5. Calcula o estorno financeiro líquido: $\text{valorEstorno} = \text{valorPago} - \text{valorTroco}$;
  6. Emite `VendaEstornadaEvent` gravado de forma transacional no Outbox (`TransactionPhase.BEFORE_COMMIT`) com chave de idempotência determinística:
     ```java
     UUID uuidReferenciaEstorno = UUID.nameUUIDFromBytes(("ESTORNO:" + vendaId).getBytes(StandardCharsets.UTF_8));
     ```
* **Consumo Idempotente no Finance:** O ouvinte AMQP [`VendaEstornadaEventListener`](../../apps/inv-service/src/main/java/inv/finance/infrastructure/messaging/VendaEstornadaEventListener.java) processa o evento inserindo lançamento negativo (`TipoLancamento.ESTORNO`) com a chave determinística, zerando o saldo da venda e alimentando as projeções CQRS e o fechamento diário D+0.
* **Feature Flag / Kill Switch:** Controlado por `features.cancelamento-venda-concluida.enabled` (retorna HTTP 503 imediatamente se desabilitado).

---

## 5. Prós e Contras (Pros & Cons)

* **Prós (+):**
  * Eliminação definitiva de deadlocks (`40P01`) e inconsistências de rollback no processamento assíncrono.
  * Atomicidade integral entre estoque, venda e outbox sob consistência ACID.
  * Auditabilidade rigorosa: pagamentos estornados e lançamentos contábeis negativos preservam histórico append-only.
  * Reconciliação matemática exata em projeções diárias e saldos de venda.
* **Contras (-):**
  * O índice parcial `V8` requer manutenção de storage adicional no banco de dados.
  * Pagamentos tardios marcados como excedentes exigem intervenção/reembolso operacional manual no gateway de pagamento.

---

## 6. Estratégia de Validação & Concorrência (Verification & Testing)

A robustez da solução é comprovada por quatro suítes de alta concorrência (totalizando 12 métodos `@Test`) executadas contra PostgreSQL no Testcontainers e pela regra estática `R15` no ArchUnit:

1. **`DevolucaoConcorrenciaDeadlockIT` (Testes 1 a 4 — 4 `@Test`):**
   * *Teste 1:* 40 cancelamentos simultâneos multi-item combinando ordem direta e ordem invertida com itens repetidos sob `CyclicBarrier(40)` — 0 deadlocks em `pg_stat_database.deadlocks`, 80 movimentações `DEVOLUCAO` e 40 eventos no Outbox.
   * *Teste 2:* Dois cancelamentos simultâneos da mesma venda concluída sob `CyclicBarrier(2)` — exatamente 1 sucesso (HTTP 200), 1 conflito (HTTP 409) e recomposição de estoque única.
   * *Teste 3:* Duas invocações diretas de `devolverEstoque` sem lock de venda sincronizadas por latch — serialização no índice parcial `V8` com colisão `23505` sem adquirir row lock em `produtos`.
   * *Teste 4:* Carga mista de 45 threads simultâneas (15 novas reservas, 15 confirmações de baixa e 15 cancelamentos) — conservação matemática rigorosa do saldo físico e 0 deadlocks.
2. **`CancelamentoPagamentoRaceIT` (Teste 5 — 4 `@Test`):**
   * *Testes 5.1, 5.2 e 5.3 (Ordem A, Ordem B1 e Ordem B2):* Validação determinística da hierarquia de locks `vendas -> produtos` sob contenção entre `handlePagamentoConfirmado` e `cancelarVendaConcluida`.
   * *Teste 5.4 (Bateria Livre):* 20 repetições em `CyclicBarrier(2)` comprovando convergência para venda `CANCELADA`, pagamentos regulares `ESTORNADO`, pagamentos tardios `excedente = true` e incremento determinístico da métrica `pagamento.excedente.total`.
3. **`LedgerConcorrenciaIdempotenciaIT` (Teste 6 — 1 `@Test`):**
   * Entregas simultâneas de `VendaPagaEvent` e `VendaEstornadaEvent` no Finance com a mesma referência UUID sob `CyclicBarrier(10)` — zero `UnexpectedRollbackException`, exatamente 1 crédito, 1 estorno e 1 `LancamentoRegistradoEvent` emitido por operação, conciliação de `saldo == 0.00` em `financial_projection_venda` e receita líquida == `0.00` na projeção diária filtrada por `data_referencia`.
4. **`PagamentoConcorrenciaGatesIT` (Gates 1, 2 e 4 — 3 `@Test`) & `ArquiteturaTest` (`R15` — Gate 3):**
   * *Gate 1:* Rajada de 10 threads simultâneas para o mesmo `pagamentoId` em `confirmarPagamentoWebhook` e `handlePagamentoConfirmado` resulta em 1 contabilização, 1 lançamento e descarte das demais entregas com ACK.
   * *Gate 2:* Contenção de lock pessimista estoura `lock_timeout` (`SQLState 55P03` -> `PessimisticLockingFailureException`) com rollback íntegro e suporte a retry/DLQ, absorvendo reentregas pós-lock com ACK.
   * *Gate 3:* Regra `R15` (`escritasEmVendaDevemUsarFindByIdWithLock`) garante que toda mutação em `Venda` existente passa por `VendaRepository.findByIdWithLock`.
   * *Gate 4:* Comprovação de `SHOW transaction_isolation == "read committed"` e de que `Venda.pagamentos` (`FetchType.LAZY`) não é carregado na sessão JPA antes do lock.

---

## 7. Referências e Links Relevantes

* [ADR-0001: Monólito Modular e Bounded Contexts](0001-modular-monolith-bounded-contexts.md)
* [ADR-0002: Modelo Híbrido de Concorrência e Estoque Atômico](0002-atomic-sql-inventory-and-hybrid-locking.md)
* [ADR-0004: Transactional Outbox Pattern](0004-transactional-outbox-with-skip-locked.md)
* [ADR-0005: Ledger Financeiro Append-Only e Idempotência](0005-append-only-financial-ledger-and-idempotency.md)
* [ADR-0011: Open Host Service Síncrono de Comandos (Checkout → Inventory)](0011-synchronous-open-host-service-checkout-inventory.md)
* [Runbook Executável DEBT-ARCH-003 E4](../debt/DEBT-ARCH-003/E4.md)
