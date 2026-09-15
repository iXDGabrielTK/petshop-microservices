# ADR-0007: Fechamento de Caixa Diário (Snapshot D+0) com Estratégia de High Watermark e Reconciliação Incremental O(Δ)

* **Status:** Aceito
* **Data:** 2026-08-28
* **Autores:** Equipe de Engenharia
* **Bounded Contexts Afetados:** Finance
* **Serviços:** `apps/inv-service`

---

## 1. Contexto e Problema (Context & Problem Statement)

No fechamento contábil diário de um varejo/pet shop, é necessário consolidar:
1. O saldo inicial do dia.
2. O montante de entradas (créditos) e saídas (estornos/débitos).
3. O saldo final do período e a integridade em relação às projeções de venda.

A abordagem ingênua de fechamento consiste em rodar queries agregadas baseadas em intervalo de datas:
```sql
-- Anti-pattern: Full Scan por Timestamp
SELECT SUM(valor) FROM lancamentos_financeiros 
WHERE data_hora >= '2026-08-27 00:00:00' AND data_hora < '2026-08-28 00:00:00';
```

**Problemas dessa abordagem:**
1. **Distorção por Fuso Horário e Drift de Relógio:** Transações com pequenas diferenças de timestamp de commit podem ser ignoradas ou contadas duas vezes entre viradas de dia.
2. **Degradação de Desempenho O(N):** Conforme a tabela atinge centenas de milhares ou milhões de linhas, o *Full Table Scan* diário consome alta memória e I/O de disco.
3. **Reconciliação Lenta:** Auditar se as projeções de todas as vendas do histórico batem com o ledger torna-se inviável em produção.

---

## 2. Drivers de Decisão (Decision Drivers / Forces)

* **Desempenho O(Δ) Constante:** O fechamento e a reconciliação devem processar apenas o delta de registros novos gerados desde o último fechamento, mantendo tempo de execução fixo independente do tamanho total da base histórica.
* **Determinismo e Imutabilidade Matemática:** O fechamento deve se basear em identificadores sequenciais crescentes (`id`), imunes a oscilações de relógio ou fusos horários.
* **Detecção Precoce de Furos Contábeis:** Capacidade de identificar divergências entre projeção e ledger com classificação de severidade (arredondamento vs erro grave).

---

## 3. Opções Consideradas (Considered Options)

1. **Opção 1: Fechamento por Range de Datas (`BETWEEN data_inicio AND data_fim`):**
   * *Problema:* Dependência frágil de timestamps e degradação de performance.
2. **Opção 2: Reconciliação Total Diária:** Recalcular a integridade de todas as vendas da história do sistema todos os dias.
   * *Problema:* Escala insustentável ($O(N)$ crescente diariamente).
3. **Opção 3: Snapshot Incremental D+0 com High Watermark e Reconciliação em Delta O(Δ):**
   * Armazenar o `ultimo_lancamento_id` consolidado.
   * Fechamento captura apenas o intervalo `id > :ultimoId AND id <= :maxId`.
   * Reconciliação audita unicamente as vendas que tiveram lançamentos dentro deste delta.

---

## 4. Decisão Tomada e Justificativa (Decision Outcome)

Decidimos adotar a **Opção 3: Snapshot Incremental D+0 com High Watermark**.

### A. Cálculo do Snapshot Atômico ([`LancamentoFinanceiroRepository.java`](../../apps/inv-service/src/main/java/inv/finance/infrastructure/persistence/LancamentoFinanceiroRepository.java#L11-L24))
Utiliza uma Common Table Expression (CTE) para fixar a "marca d'água alta" (*High Watermark*) de forma atômica, garantindo que novas inserções concorrentes durante a execução da query não afetem o cálculo:

```sql
WITH limite AS (
   SELECT COALESCE(MAX(id), 0) AS max_id FROM lancamentos_financeiros
)
SELECT
   limite.max_id AS ultimoLancamentoId,
   COALESCE(SUM(CASE WHEN valor > 0 THEN valor ELSE 0 END), 0) AS creditos,
   COALESCE(SUM(CASE WHEN valor < 0 THEN ABS(valor) ELSE 0 END), 0) AS debitos
FROM lancamentos_financeiros, limite
WHERE lancamentos_financeiros.id > :ultimoId
  AND lancamentos_financeiros.id <= limite.max_id
GROUP BY limite.max_id;
```

### B. Execução do Fechamento Transacional ([`FechamentoCaixaService.java`](../../apps/inv-service/src/main/java/inv/finance/usecase/FechamentoCaixaService.java#L30-L62))
* Saldo Final é calculado deterministicamente: \(SaldoFinal = SaldoInicial + Creditos - Debitos\).
* Um registro imutável é gravado na tabela `fechamento_caixa_diario`.

### C. Reconciliação Incremental O(Δ) ([`ReconciliacaoRepository.java`](../../apps/inv-service/src/main/java/inv/finance/infrastructure/persistence/ReconciliacaoRepository.java#L15-L33))
A reconciliação não varre todas as vendas da história, mas apenas o subconjunto que sofreu mutação no delta:

```sql
WITH vendas_movimentadas AS (
    SELECT DISTINCT venda_id
    FROM lancamentos_financeiros
    WHERE id > :ultimoReconhecido AND id <= :novoMaxId
)
SELECT
    fpv.venda_id AS vendaId,
    fpv.saldo AS saldoProjetado,
    SUM(lf.valor) AS saldoReal,
    ABS(fpv.saldo - SUM(lf.valor)) AS divergencia
FROM vendas_movimentadas vm
JOIN lancamentos_financeiros lf ON lf.venda_id = vm.venda_id
JOIN financial_projection_venda fpv ON fpv.venda_id = vm.venda_id
GROUP BY fpv.venda_id, fpv.saldo
HAVING fpv.saldo <> SUM(lf.valor);
```

### D. Classificação de Severidade de Divergência ([`ReconciliacaoService.java`](file:///c:/Users/gabri/IdeaProjects/petshop-microservices/apps/inv-service/src/main/java/inv/finance/usecase/ReconciliacaoService.java#L28-L42))

> [!NOTE]
> **Aviso de Governança de Produto:** Os valores de corte abaixo foram definidos heuristicamente pelo time de engenharia durante a implementação para diferenciar ruídos de arredondamento de bugs de código. Eles constam como **valores provisórios**, pendentes de validação e calibração formal junto à área contábil/financeira de negócio.

* **Diferença \(\le R\$\ 0,01\):** `WARN` (Arredondamento fracionário aceitável).
* **Diferença \(\le R\$\ 1,00\):** `ALERT` (Divergência suspeita para revisão).
* **Diferença \(> R\$\ 1,00\):** `CRITICAL` (Furo financeiro grave disparado para os canais de alerta da engenharia).

---

## 5. Prós e Contras das Opções Analisadas

* **Positivas (+):**
  * Tempo de processamento estritamente proporcional ao número de vendas do dia (\(O(\Delta)\)), não ao volume acumulado de anos (\(O(N)\)).
  * Imunidade total a discrepâncias de fuso horário ou relógios desajustados.
  * Trilha de fechamentos imutável e facilmente auditável.
* **Negativas / Desafios (-):**
  * Exige que os identificadores de lançamentos sejam estritamente sequenciais e monotonicamente crescentes (`BIGSERIAL`).

---

## 6. Estratégia de Validação & Testes

* **Teste de Fechamento com Múltiplos Dias:**
  1. Inserir lote de vendas no Dia 1 e rodar `executarFechamentoTransacional()`. Validar saldo e `ultimo_lancamento_id`.
  2. Inserir novo lote no Dia 2 e rodar novamente.
  3. Validar se o `saldo_inicial` do Dia 2 bate com o `saldo_final` do Dia 1 e se apenas os novos IDs foram contabilizados.
* **Teste de Detecção de Furo Contábil:**
  * Forçar manualmente um valor divergente na tabela `financial_projection_venda`.
  * Rodar o `ReconciliacaoService` e validar se a divergência foi detectada e classificada corretamente no log.

---

## 7. Referências e Links Relevantes

* [Serviço de Fechamento de Caixa](../../apps/inv-service/src/main/java/inv/finance/usecase/FechamentoCaixaService.java)
* [Serviço de Reconciliação](../../apps/inv-service/src/main/java/inv/finance/usecase/ReconciliacaoService.java)
* [Repositório de Lançamentos com High Watermark](../../apps/inv-service/src/main/java/inv/finance/infrastructure/persistence/LancamentoFinanceiroRepository.java)
* [Documentação do Domínio Finance](../domains/finance.md)
