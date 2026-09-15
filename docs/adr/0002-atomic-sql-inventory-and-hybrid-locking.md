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

### A. Operações Atômicas de Estoque ([`ProdutoRepository.java`](../../apps/inv-service/src/main/java/inv/inventory/infrastructure/persistence/ProdutoRepository.java#L27-L50))
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

### B. Orquestração de Venda com Retries ([`VendaService.java`](../../apps/inv-service/src/main/java/inv/checkout/usecase/VendaService.java#L67-L87))
Para a criação da venda e agregação de múltiplos itens:
* A transação utiliza `@Retryable` com backoff exponencial aleatório (`150ms` a `500ms`) para absorver colisões transitórias.

### C. Liquidação Segura do Pagamento ([`VendaService.java`](../../apps/inv-service/src/main/java/inv/checkout/usecase/VendaService.java#L129-L155))
Quando um webhook de confirmação chega:
* A aplicação obtém um lock pessimista (`vendaRepository.findByIdWithLock(vendaId)`) para garantir que o registro do pagamento e a transição para `CONCLUIDA` ocorram de forma totalmente isolada de jobs de timeout ou webhooks duplicados.

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
> Este teste está implementado na suíte de testes de integração e é executado contra uma instância real do PostgreSQL 15 via Testcontainers.
> Classe de teste: [`EstoqueConcorrenciaIntegrationTest`](../../apps/inv-service/src/test/java/inv/inventory/EstoqueConcorrenciaIntegrationTest.java)
> Comando de execução:
> ```bash
> mvn test -pl apps/inv-service -Dtest=EstoqueConcorrenciaIntegrationTest
> ```

### Cenário de Teste Automatizado: *Stress Test de Esgotamento de Estoque*
* **Classe:** `inv.inventory.EstoqueConcorrenciaIntegrationTest`
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

## 7. Referências e Links Relevantes

* [Implementação do Repositório Atômico](../../apps/inv-service/src/main/java/inv/inventory/infrastructure/persistence/ProdutoRepository.java)
* [Implementação do VendaService com Retries](../../apps/inv-service/src/main/java/inv/checkout/usecase/VendaService.java)
* [Documentação do Domínio de Inventário](../domains/inventory.md)
* [PostgreSQL Documentation - Concurrency Control & RETURNING clause](https://www.postgresql.org/docs/current/dml-returning.html)
