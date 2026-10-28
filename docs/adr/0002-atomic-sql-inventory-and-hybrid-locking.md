# ADR-0002: Controle Híbrido de Concorrência: Atomic SQL (`UPDATE ... RETURNING`), Optimistic Retry e Pessimistic Lock

* **Status:** Aceito
* **Data:** 2026-08-28
* **Autores:** Equipe de Engenharia
* **Bounded Contexts Afetados:** Inventory, Checkout
* **Serviços:** `apps/inv-service`

---

## 1. Contexto e Problema (Context & Problem Statement)

Em um ambiente de varejo/pet shop com múltiplos terminais de ponto de venda (PDV) e vendas online simultâneas, produtos com alta demanda ou poucas unidades em estoque (ex: ração premium em promoção) sofrem concorrência severa.

O anti-pattern clássico de concorrência é o **Read-Modify-Write**:
1. A thread lê a quantidade de estoque (`SELECT quantidade FROM produtos WHERE id = 1` -> retorna 1).
2. A aplicação verifica na memória se `quantidade >= 1` (retorna true).
3. A aplicação subtrai 1 e executa `UPDATE produtos SET quantidade = 0 WHERE id = 1`.

Se duas threads realizarem o passo 1 no mesmo milissegundo, ambas lerão estoque = 1 e ambas aprovarão a venda, resultando em **venda a descoberto (overselling/estoque negativo)** e furos operacionais graves.

Além disso, a finalização de uma venda envolve confirmações de pagamento via webhooks e jobs de expiração de pedidos, onde confirmações duplicadas não podem colidir com o cancelamento por timeout.

---

## 2. Drivers de Decisão (Decision Drivers / Forces)

* **Consistência Absoluta no Estoque (Zero Overselling):** É inaceitável permitir estoque disponível negativo sob qualquer carga de requisições.
* **Throughput Máximo e Baixa Contenção:** Evitar bloqueios globais de tabela ou locks pessimistas longos durante a navegação/carrinho do cliente.
* **Resiliência a Race Conditions em Pagamento:** Garantir que webhooks simultâneos do gateway de pagamento ou colisões entre pagamento e cancelamento por timeout sejam processados atomicamente.
* **Experiência do Usuário (UX):** Retries automáticos transparentes no caso de concorrência passageira.

---

## 3. Opções Consideradas (Considered Options)

1. **Opção 1: Pessimistic Locking Puro em Tudo (`SELECT ... FOR UPDATE`):** Bloquear a linha do produto no banco desde a leitura inicial até o commit da venda.
   * *Problema:* Serializa todas as leituras daquele produto, causando filas, alto tempo de resposta e risco elevado de deadlocks cruzados quando um pedido tem múltiplos itens.
2. **Opção 2: Optimistic Locking Puro com `@Version` em Produto:** Usar coluna de versão JPA em `Produto`.
   * *Problema:* Sob alta concorrência (ex: 50 caixas vendendo o mesmo item ao mesmo tempo), 49 requisições tomam `OptimisticLockException` imediatamente, degradando o throughput mesmo que ainda houvesse 500 itens em estoque.
3. **Opção 3: Modelo Híbrido: Atomic SQL com `RETURNING` + Optimistic Retry + Pessimistic Lock de Liquidação:**
   * **Estoque:** `UPDATE ... WHERE estoque_disponivel >= :qtd RETURNING estoque_disponivel` atômico no PostgreSQL.
   * **Abertura de Venda:** `@Retryable` com backoff aleatório para lidar com contenção transacional no pedido.
   * **Liquidação de Pagamento:** Lock pessimista (`findByIdWithLock`) apenas no momento exato em que a venda muda para `CONCLUIDA`.

---

## 4. Decisão Tomada e Justificativa (Decision Outcome)

Decidimos adotar a **Opção 3: Modelo Híbrido de Concorrência**.

### A. Operações Atômicas de Estoque ([`ProdutoRepository.java`](../../apps/inv-service/src/main/java/inv/inventory/infrastructure/persistence/ProdutoRepository.java#L45-L69))
Ao executar o update atômico condicional diretamente no engine MVCC do PostgreSQL, o lock de linha dura apenas o tempo da instrução SQL (microssegundos):

```sql
UPDATE produtos 
SET estoque_disponivel = estoque_disponivel - :qtd,
    estoque_reservado = estoque_reservado + :qtd
WHERE id = :id AND estoque_disponivel >= :qtd 
RETURNING estoque_disponivel;
```
* Se o resultado for nulo (0 linhas afetadas), a aplicação sabe imediatamente que o estoque se esgotou sem precisar de lock prévio.
* A baixa definitiva (`confirmarBaixaEstoqueAtomo`) e a devolução por cancelamento (`estornarReservaEstoqueAtomo`) seguem a mesma semântica atômica.

### B. Orquestração de Venda com Retries ([`VendaService.java`](../../apps/inv-service/src/main/java/inv/checkout/usecase/VendaService.java#L50-L69))
Para a criação da venda e agregação de múltiplos itens:
* A transação utiliza `@Retryable` com backoff exponencial aleatório (`150ms` a `500ms`) para absorver colisões transitórias.

### C. Liquidação Segura do Pagamento e Expiração ([`VendaEventHandler.java`](../../apps/inv-service/src/main/java/inv/checkout/infrastructure/messaging/VendaEventHandler.java#L42-L82) e [`VendaService.java`](../../apps/inv-service/src/main/java/inv/checkout/usecase/VendaService.java#L110-L128))
Quando um webhook de confirmação chega:
* Na expiração (`cancelarVendaExpirada`), a aplicação obtém um lock pessimista (`vendaRepository.findByIdWithLock(vendaId)`) e aplica o guard pós-lock, enquanto na liquidação (`VendaEventHandler`) combina o versionamento otimista (`@Version`) de `Venda` com `@Retryable`, garantindo que a transição para `CONCLUIDA` e o cancelamento por timeout sejam mutuamente exclusivos.

### Consequências e Trade-offs

* **Positivas (+):**
  * Eliminação total de *overselling* (garantido por restrição atômica no banco).
  * Altíssimo throughput: produtos com muito estoque não sofrem lentidão nem conflito de versão.
  * Proteção contra deadlocks: ordenação implícita e tempo de lock atômico infinitesimal.
* **Negativas / Desafios (-):**
  * Queries nativas SQL exigem cuidado adicional em manutenções e não dependem do gerenciamento automático de estado do EntityManager do Hibernate.

---

## 5. Prós e Contras das Opções Analisadas

| Abordagem | Throughput em Alta Carga | Risco de Overselling | Risco de Deadlock | Complexidade |
| :--- | :--- | :--- | :--- | :--- |
| **Pessimistic Lock Puro** | Baixo (Serialização) | Zero | Alto | Média |
| **Optimistic Lock Puro (`@Version`)** | Médio (Muitas exceções) | Zero | Baixo | Baixa |
| **Híbrido (Atomic SQL + Lock de Venda)** | **Altíssimo** | **Zero** | **Baixíssimo** | Média |

---

## 6. Estratégia de Validação & Testes de Carga/Concorrência (Verification & Testing)

> [!IMPORTANT]
> Este teste está implementado na suíte de testes de integração cross-context (`inv.it`) e é executado contra uma instância real do PostgreSQL 15 via Testcontainers.
> Classe de teste: [`EstoqueConcorrenciaIntegrationTest`](../../apps/inv-service/src/test/java/inv/it/EstoqueConcorrenciaIntegrationTest.java)
> Comando de execução:
> ```bash
> mvn test -pl apps/inv-service -Dtest=EstoqueConcorrenciaIntegrationTest
> ```

### Cenário de Teste Automatizado: *Stress Test de Esgotamento de Estoque*
* **Classe:** `inv.it.EstoqueConcorrenciaIntegrationTest` (movida de `inv.inventory` na Etapa 4 do [DEBT-ARCH-002](../debt/DEBT-ARCH-002/README.md))
* **Configuração do Ambiente:** Container PostgreSQL 15 via Testcontainers (`postgres:15-alpine`).
* **Massa de Teste:**
  * Produto criado com `estoque_disponivel = 5.000` e `estoque_reservado = 0.000`.
  * Carga: **50 threads simultâneas** disparadas pelo `ExecutorService`.
  * Cada thread tenta executar `iniciarVenda()` solicitando **1 unidade**.
* **Mecanismo de Sincronismo:**
  * Uso de `CountDownLatch(1)` como gatilho de largada simultânea (*start gate*) para forçar todas as 50 threads a atingirem o banco de dados no mesmo instante exato.
  * Uso de `CountDownLatch(50)` para aguardar a conclusão de todas as execuções.

### Critérios de Aceite Obrigatórios (Asserts Validados)
1. **Contagem de Sucesso:** Exatamente **5 threads** completam a reserva com sucesso (HTTP 200 / Venda criada).
2. **Contagem de Rejeição:** Exatamente **45 threads** recebem `BusinessException` por falta de estoque disponível.
3. **Consistência Física do Banco:**
   * `estoque_disponivel` final é **exatamente 0.000**.
   * `estoque_reservado` final é **exatamente 5.000**.
   * Soma de `estoque_disponivel + estoque_reservado` é igual ao estoque inicial (5.000).
4. **Ausência de Erros de Infraestrutura:** Zero ocorrências de `PSQLException: deadlock detected` ou conexões presas no pool HikariCP.
5. **Integridade de Vendas:** Exatamente 5 vendas registradas no banco.

---

## 7. Adendo (Outubro/2026 — DEBT-ARCH-002): Ordenação Determinística por `produtoId`, Hierarquia de Locks, Pré-Imagem Anti-Spam e Posse do Protocolo por `EstoqueCommandService`

Durante a execução do plano **[DEBT-ARCH-002](../debt/DEBT-ARCH-002/README.md)** ([ADR-0011](0011-synchronous-open-host-service-checkout-inventory.md)), o protocolo de concorrência desta ADR foi refinado e blindado contra deadlocks multi-item (`40P01`), corridas de leitura no alerta anti-spam e estornos duplicados:

### A. Posse do Protocolo pelo Inventory (`EstoqueCommandService`)
* **Contexto (Achado F4 e Decisões Q1/Q2):** Anteriormente, `VendaService` e `VendaEventHandler` carregavam entidades `Produto` e iteravam item a item invocando `EstoqueService` diretamente. A política de concorrência vazava para o Checkout.
* **Solução:** O protocolo de reserva (*Try*), confirmação de baixa (*Confirm*) e estorno de reserva (*Cancel*) passa a ser de posse exclusiva de [`EstoqueCommandService`](../../apps/inv-service/src/main/java/inv/inventory/api/EstoqueCommandService.java) (implementado por [`EstoqueService`](../../apps/inv-service/src/main/java/inv/inventory/usecase/EstoqueService.java), `package-private`), operando em lote via `ComandoEstoqueVenda` e exigindo transação ativa do chamador (`Propagation.MANDATORY`).

### B. Ordenação Determinística por `produtoId ASC` e Proibição de `UPDATE ... FROM (VALUES ...)` (Decisão Q3 / Achado F5)
* **Prevenção Matemática de Deadlocks `40P01`:** Todos os três comandos de lote (`reservar`, `confirmarBaixa`, `estornarReserva`) ordenam internamente os `ItemComando` por `produtoId` crescente (`Comparator.comparingLong(ItemComando::produtoId)`, ordenação estável) **antes** de executar os `UPDATE`s atômicos, independentemente da ordem enviada pelo chamador. Como todo escritor adquire row locks (`FOR NO KEY UPDATE`) em `produtos` em ordem estritamente crescente de `id`, é impossível formar ciclo no grafo de espera (*Wait-For Graph*) entre carrinhos concorrentes (ex.: `[A, B]` vs `[B, A]`).
* **Proibição de `UPDATE ... FROM (VALUES ...)`:** As três instruções SQL nativas de [`ProdutoRepository`](../../apps/inv-service/src/main/java/inv/inventory/infrastructure/persistence/ProdutoRepository.java) permanecem executadas **uma por item** na ordem classificada. É expressamente proibido substituir o laço ordenado por um único `UPDATE produtos FROM (VALUES ...)`, pois o planejador do PostgreSQL pode escolher estratégias de join (*Hash Join*, *Merge Join*) que não garantem a ordem física de visitação e aquisição dos locks de tupla.
* **Compatibilidade de Locks de FK:** Os `INSERT`s em `itens_venda` e `movimentacoes_estoque` adquirem apenas `FOR KEY SHARE` na linha pai de `produtos`, que é compatível com `FOR NO KEY UPDATE` (já que a PK `produtos.id` não é modificada nos `UPDATE`s de saldo), não adicionando arestas de bloqueio.

### C. Hierarquia de Locks (`vendas → produtos`), Guard Pós-Lock e Resiliência com `@Retryable` (Decisões Q8 e Q10 / Achado F2)
* **Hierarquia `vendas → produtos`:** Nas operações de liquidação e expiração, o lock em `vendas` precede os locks em `produtos` (ordenados por `produtoId ASC`), impedindo deadlocks cruzados entre `VendaEventHandler` e `VendaExpiracaoJob`.
* **Guard Idempotente em `cancelarVendaExpirada` (F2/Q10):** Logo após `vendaRepository.findByIdWithLock(vendaId)`, se a venda já estiver `CANCELADA`, o método registra log e retorna de forma idempotente sem invocar o estorno (evitando consumir reserva de terceiros — *roubo de reserva*); se o status estiver fora de `{ABERTA, AGUARDANDO_PAGAMENTO}` (ex.: `CONCLUIDA` ou `CANCELAMENTO_SOLICITADO`), lança `BusinessException`.
* **Retry para Lock Pessimista (Q8):** O `@Retryable` de `VendaService.iniciarVenda` inclui `PessimisticLockingFailureException.class` (além de `OptimisticLockingFailureException.class`), e o método `@Recover` captura `ConcurrencyFailureException`, atuando como rede de segurança adicional à ordenação determinística.

### D. Pré-Imagem `RETURNING + qtd` no Alerta Anti-Spam e Eliminação de Hidratação de Entidade (Achado F6)
* **Pré-Imagem Exata sob o Lock:** Para eliminar a leitura obsoleta de `estoqueDisponivel` antes do lock, `EstoqueService.reservar` reconstrói o saldo imediatamente anterior à reserva a partir do valor retornado pela cláusula `RETURNING estoque_disponivel` sob o row lock atômico:
  $$\text{saldoAnterior} = \text{novoSaldo} + \text{quantidadeReservada}$$
  Isso garante que a condição de transição de limiar ($\text{saldoAnterior} > \text{estoqueMinimo} \land \text{novoSaldo} \le \text{estoqueMinimo}$) seja avaliada com precisão matemática sob alta concorrência, disparando o `EstoqueAtingiuMinimoEvent` exatamente uma única vez na quebra do limite.
* **Zero `SELECT` de Entidade `Produto`:** Os metadados necessários na reserva (`id`, `nome`, `estoqueMinimo`) são buscados em uma única projeção leve em lote (`ProdutoMetadadosEstoque`), e a associação em `MovimentacaoEstoque` utiliza `produtoRepository.getReferenceById(produtoId)` (proxy Hibernate sem round-trip SQL).

### E. Suíte Expandida de Testes de Concorrência (`inv.it`)
Além do `EstoqueConcorrenciaIntegrationTest`, a blindagem concorrente é validada por:
* [`CarrinhosInvertidosDeadlockIT`](../../apps/inv-service/src/test/java/inv/it/CarrinhosInvertidosDeadlockIT.java): 20 pares de carrinhos invertidos (`[A, B]` vs `[B, A]`, 40 threads em `CyclicBarrier`) com `0` deadlocks em `pg_stat_database.deadlocks`, acompanhado de controle negativo que força e comprova determinismo do deadlock `40P01` quando a ordem é violada;
* [`EstoqueMultiItemConcorrenciaIT`](../../apps/inv-service/src/test/java/inv/it/EstoqueMultiItemConcorrenciaIT.java): 50 threads disputando carrinhos multi-item invertidos com rollback atômico integral do lote quando um item subsequente falha;
* [`HandlerExpiracaoMesmaVendaIT`](../../apps/inv-service/src/test/java/inv/it/HandlerExpiracaoMesmaVendaIT.java): corrida simultânea em `CyclicBarrier` entre confirmação de pagamento e expiração da mesma venda (30 repetições) e corrida cruzada entre confirmação de baixa `[A, B]` e estorno `[B, A]`;
* [`IniciarVendaRetryIT`](../../apps/inv-service/src/test/java/inv/it/IniciarVendaRetryIT.java) e [`CancelamentoExpiradoIdempotenteIT`](../../apps/inv-service/src/test/java/inv/it/CancelamentoExpiradoIdempotenteIT.java).

---

## 8. Referências e Links Relevantes

* [Contrato EstoqueCommandService](../../apps/inv-service/src/main/java/inv/inventory/api/EstoqueCommandService.java)
* [Implementação EstoqueService (Lote Ordenado)](../../apps/inv-service/src/main/java/inv/inventory/usecase/EstoqueService.java)
* [Implementação do Repositório Atômico](../../apps/inv-service/src/main/java/inv/inventory/infrastructure/persistence/ProdutoRepository.java)
* [Implementação do VendaService com Retries](../../apps/inv-service/src/main/java/inv/checkout/usecase/VendaService.java)
* [ADR-0011: Open Host Service Síncrono de Comandos e Catálogo (Checkout → Inventory)](0011-synchronous-open-host-service-checkout-inventory.md)
* [Documentação do Domínio de Inventário](../domains/inventory.md)
* [PostgreSQL Documentation - Concurrency Control & RETURNING clause](https://www.postgresql.org/docs/current/dml-returning.html)
