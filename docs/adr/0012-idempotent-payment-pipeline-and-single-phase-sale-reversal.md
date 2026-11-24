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
   * *Gate 2:* Contenção de lock pessimista estoura `lock_timeout` (`SQLState 55P03` -> `PessimisticLockingFailureException`) com rollback íntegro, absorvendo reentregas pós-lock com ACK. Gate 2 valida estritamente o contrato de exceções da aplicação (`PessimisticLockingFailureException` para retentativa com rollback íntegro e `AmqpRejectAndDontRequeueException` para descarte sem requeue). O roteamento AMQP físico para a fila DLQ e os interceptores de retry dependem da infraestrutura do broker RabbitMQ, mockada na suíte de testes.
   * *Gate 3:* Regra `R15` (`escritasEmVendaDevemUsarFindByIdWithLock`) verifica a ordem entre as chamadas de lock e as escritas reconhecidas no mesmo método (ver adendo da seção 8).
   * *Gate 4:* Comprovação de `SHOW transaction_isolation == "read committed"` e de que `Venda.pagamentos` (`FetchType.LAZY`) não é carregado na sessão JPA antes do lock.

---

## 7. Adendo (Novembro/2026 — DEBT-ARCH-004): Protocolo de Idempotência em 6 Etapas na Intenção de Pagamento, Parametrização da Feature Flag e Padronização RFC 7807 (`409`/`503`)

Durante a execução do épico **[DEBT-ARCH-004](../debt/DEBT-ARCH-004/README.md)**, o pipeline de pagamento, o controle operacional do cancelamento e as respostas de erro HTTP foram endurecidos e padronizados nos seguintes pontos:

### A. Protocolo de Idempotência em 6 Etapas em `PagamentoService.criarIntencaoPagamento` e Validação Decimal Sem Arredondamento (`E3`)
* **Validação Decimal Sem Arredondamento:** [`CriarIntencaoPagamentoRequest`](../../apps/inv-service/src/main/java/inv/checkout/infrastructure/web/dto/CriarIntencaoPagamentoRequest.java) declara `@Positive` e `@Digits(integer = 8, fraction = 2)`, normalizando zeros à direita em seu construtor compacto (`stripTrailingZeros()`, ajustando escalas negativas com `setScale(0)`) para aceitar `10.000`, `10.00` e `100` e rejeitar com HTTP `400` valores com mais de 2 casas decimais significativas (ex.: `10.005`), evitando arredondamentos silenciosos em `NUMERIC(10, 2)` e falsos `409` em retries. [`PagamentoService`](../../apps/inv-service/src/main/java/inv/checkout/usecase/PagamentoService.java#L42-L55) aplica defesa em profundidade rejeitando `valor == null`, `<= 0`, `> 99999999.99` ou `scale() > 2` com `BusinessException` (`400`).
* **Protocolo em 6 Etapas (`criarIntencaoPagamento`):**
  1. **Consulta rápida:** `findByUuidReferencia(uuidReferencia)` retorna o `pagamentoId` (`200`) se `vendaId`, `metodo` e `valorEsperado.compareTo(valor) == 0` coincidirem (inclusive se a venda já estiver `CONCLUIDA` ou `CANCELADA`), ou lança `ConflictException` (`409`) se divergirem.
  2. **Lock pessimista na venda:** `VendaRepository.findByIdWithLock(vendaId)` (`ResourceNotFoundException` `404` se inexistente), serializando a criação de intenção com conclusão, expiração e cancelamento na hierarquia global `vendas -> produtos`.
  3. **Reconsulta pós-lock:** executa novamente `findByUuidReferencia(uuidReferencia)` antes de validar o status da venda, preservando retries legítimos de transações concorrentes que mudaram a venda durante a espera pelo lock.
  4. **Validação de status da venda:** exige `venda.getStatus().aceitaPagamento()`; caso contrário, lança `ConflictException` (`409`).
  5. **Inserção atômica com fonte única do domínio:** instancia `Pagamento` (aplicando `processar()` quando `metodo.isExigeProcessamento()` for `true`) e executa [`PagamentoRepository.inserirIntencaoIdempotente`](../../apps/inv-service/src/main/java/inv/checkout/infrastructure/persistence/PagamentoRepository.java#L29-L50) (`INSERT INTO pagamentos ... ON CONFLICT (uuid_referencia) DO NOTHING RETURNING id`).
  6. **Fallback em colisão concorrente (`uk_pagamento_uuid`):** caso `Optional<Long>` venha vazio, reconsulta `findByUuidReferencia(uuidReferencia)` e aplica a validação compartilhada (`200` se igual; `ConflictException` `409` revertendo a transação se divergente, sem sobrescrever o registro existente).

### B. Parametrização da Feature Flag `FEATURES_CANCELAMENTO_VENDA_CONCLUIDA_ENABLED` e Autorização (`E1`)
* **Kill Switch Parametrizado:** `features.cancelamento-venda-concluida.enabled` passou a ser configurada via variável de ambiente `FEATURES_CANCELAMENTO_VENDA_CONCLUIDA_ENABLED` em `application.properties`, `docker-compose.yml` e `.env.example`, mantendo **`false`** como valor padrão seguro.
* **Fronteira com o Adquirente (`DEBT-PAY-001`):** o cancelamento atual executa estorno interno/contábil (`StatusPagamento.ESTORNADO`, devolução física de estoque e lançamento `ESTORNO` no Finance); a integração de reembolso real no adquirente pertence ao backlog `DEBT-PAY-001`, exigindo conciliação operacional manual validada para pagamentos `ESTORNADO` e `excedente = true` antes de habilitar a flag com pagamentos reais.
* **Autorização JWT Alinhada:** `POST /vendas/{id}/cancelar` é protegido por `@PreAuthorize("hasRole('ADMIN')")` em [`VendaController`](../../apps/inv-service/src/main/java/inv/checkout/infrastructure/web/controller/VendaController.java), validado na cadeia real de filtros JWT por [`VendaSecurityFilterTest`](../../apps/inv-service/src/test/java/inv/checkout/infrastructure/web/controller/VendaSecurityFilterTest.java) ([ADR-0009](0009-centralized-oauth2-oidc-and-stateless-resource-servers.md)).

### C. Devolução Estritamente Integral contra `SAIDA`s (`E4`)
* `EstoqueService.devolverEstoque` passou a exigir integralidade estrita contra todas as movimentações `SAIDA` da venda (`MovimentacaoRepository.buscarSaidasPorVenda`) e igualdade de quantidade em repetições de `DEVOLUCAO`, apoiado pelo índice parcial concorrente **`idx_movimentacoes_venda_saida`** da migration [`V9__indice_movimentacoes_estoque_saida.sql`](../../apps/inv-service/src/main/resources/db/migration/V9__indice_movimentacoes_estoque_saida.sql) (detalhes em [ADR-0002](0002-atomic-sql-inventory-and-hybrid-locking.md) e [ADR-0011](0011-synchronous-open-host-service-checkout-inventory.md)).

### D. Padronização RFC 7807 (`ProblemDetail`) para HTTP `409` e `503` via `common-lib` (`E3` e `E5`)
* Introduzidas [`ConflictException`](../../apps/common-lib/src/main/java/common/exception/ConflictException.java) (`409`, `title: "Conflito de Estado"`, `type: "https://petshop.com/errors/conflict"`) e [`ServiceUnavailableException`](../../apps/common-lib/src/main/java/common/exception/ServiceUnavailableException.java) (`503`, `title: "Serviço Indisponível"`, `type: "https://petshop.com/errors/service-unavailable"`) no [`GlobalExceptionHandler`](../../apps/common-lib/src/main/java/common/exception/GlobalExceptionHandler.java) da `common-lib`.
* [`CancelamentoVendaConflitoException`](../../apps/inv-service/src/main/java/inv/checkout/usecase/CancelamentoVendaConflitoException.java) passou a estender `ConflictException` e [`CancelamentoVendaDesabilitadoException`](../../apps/inv-service/src/main/java/inv/checkout/usecase/CancelamentoVendaDesabilitadoException.java) passou a estender `ServiceUnavailableException`; os `@ExceptionHandler` locais de `VendaController` (e o formato legado `{"mensagem": "..."}`) foram integralmente removidos.

---

## 8. Adendo (Novembro/2026 — DEBT-ARCH-005): Detecção de Escrita Pré-Lock pela R15

A [R15 em `ArquiteturaTest`](../../apps/inv-service/src/test/java/inv/architect/ArquiteturaTest.java) inspeciona o bytecode dos métodos do Checkout e usa os números de linha para comparar `VendaRepository.findByIdWithLock` com a primeira escrita detectada. O cálculo inclui tanto chamadas mutadoras em `Venda` quanto as escritas diretas no repositório reconhecidas pela regra. Assim, `saveAndFlush(venda)` antes do lock é apontado mesmo quando `venda.cancelar()` aparece depois dele. A análise é intraprocedural e depende das chamadas visíveis no método. O fluxo por métodos auxiliares e a correspondência entre o ID do lock e a instância mutada ficam fora dessa verificação.

[`ArquiteturaR15Test`](../../apps/inv-service/src/test/java/inv/architect/ArquiteturaR15Test.java) aplica a própria regra às fixtures via `ClassFileImporter`. Os casos de ordem correta passam e cada inversão produz uma única violação de escrita pré-lock. Os casos negativos incluem três fluxos-base válidos para satisfazer o piso de cobertura da regra. A prova sintética cobre essa inversão. Os cenários de concorrência em execução continuam em `PagamentoConcorrenciaGatesIT`. A [ADR-0013](0013-surefire-failsafe-test-lifecycle.md) descreve a execução das duas suítes.

---

## 9. Referências e Links Relevantes

* [Plano Executivo DEBT-ARCH-004](../debt/DEBT-ARCH-004/README.md)
* [Runbook Executável DEBT-ARCH-003 E4](../debt/DEBT-ARCH-003/E4.md)
* [GlobalExceptionHandler (common-lib)](../../apps/common-lib/src/main/java/common/exception/GlobalExceptionHandler.java)
* [ConflictException (common-lib)](../../apps/common-lib/src/main/java/common/exception/ConflictException.java)
* [ServiceUnavailableException (common-lib)](../../apps/common-lib/src/main/java/common/exception/ServiceUnavailableException.java)
* [ADR-0001: Monólito Modular e Bounded Contexts](0001-modular-monolith-bounded-contexts.md)
* [ADR-0002: Modelo Híbrido de Concorrência e Estoque Atômico](0002-atomic-sql-inventory-and-hybrid-locking.md)
* [ADR-0004: Transactional Outbox Pattern](0004-transactional-outbox-with-skip-locked.md)
* [ADR-0005: Ledger Financeiro Append-Only e Idempotência](0005-append-only-financial-ledger-and-idempotency.md)
* [ADR-0009: Arquitetura de Autenticação com Spring Authorization Server e RSA](0009-centralized-oauth2-oidc-and-stateless-resource-servers.md)
* [ADR-0011: Open Host Service Síncrono de Comandos (Checkout → Inventory)](0011-synchronous-open-host-service-checkout-inventory.md)
* [Documentação do Domínio Checkout](../domains/checkout.md)
