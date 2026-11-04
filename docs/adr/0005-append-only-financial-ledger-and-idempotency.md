# ADR-0005: Ledger Financeiro Imutável (Append-Only) com Chave de Idempotência UUID

* **Status:** Aceito
* **Data:** 2026-08-28
* **Autores:** Equipe de Engenharia
* **Bounded Contexts Afetados:** Finance, Checkout
* **Serviços:** `apps/inv-service`

---

## 1. Contexto e Problema (Context & Problem Statement)

A gestão financeira tradicional em aplicações CRUD frequentemente utiliza colunas mutáveis de saldo (ex: `UPDATE contas SET saldo = saldo + 100`).

Em ambientes distribuídos e assíncronos (com múltiplos caixas de PDV, webhooks de operadoras de cartão, estornos parciais e retries de rede), a mutação direta de saldo apresenta falhas fatais:
1. **Perda de Trilha de Auditoria:** Impossível reconstruir o estado histórico ou identificar a origem de uma discrepância contábil.
2. **Race Conditions e Furos Contábeis:** Duas transações concorrentes atualizando o mesmo saldo geram perdas de atualização (*lost updates*) caso ocorra falha de isolamento.
3. **Duplicação por Retries de Webhook:** Gateways de pagamento frequentemente reenviam o mesmo webhook de confirmação caso ocorra timeout de rede. Sem uma restrição de idempotência rígida no ledger, o saldo da venda seria creditado duas vezes.

---

## 2. Drivers de Decisão (Decision Drivers / Forces)

* **Auditabilidade e Imutabilidade:** Cada centavo que entra ou sai deve ser um registro histórico imutável (Journaling / Princípio das Partidas Dobradas).
* **Idempotência Absoluta no Nível de Dados:** Nenhuma transação pode ser registrada mais de uma vez, mesmo sob múltiplos disparos da mesma mensagem.
* **Consistência Contábil:** O saldo de qualquer entidade é uma função determinística da soma de todos os seus lançamentos históricos (\(Saldo = \sum Lancamentos\)).
* **Simplicidade de Raciocínio Concorrente:** Inserções (`INSERT`) puras não sofrem contenção de lock de linha como atualizações (`UPDATE`) em linhas compartilhadas.

---

## 3. Opções Consideradas (Considered Options)

1. **Opção 1: Saldo Mutável Tradicional com Lock Pessimista:** Manter uma tabela `saldos_caixa` com `saldo_atual` e fazer `SELECT ... FOR UPDATE` a cada lançamento.
   * *Problema:* Gargalo severo de banco de dados, bloqueios constantes e perda de histórico de lançamentos intermediários.
2. **Opção 2: Tabela de Event Sourcing Genérica (JSON Event Store):** Gravar eventos não estruturados em uma tabela de eventos genérica (`event_store`).
   * *Problema:* Complexidade excessiva para queries analíticas e agregações financeiras SQL nativas.
3. **Opção 3: Ledger Financeiro Relacional Append-Only com Restrição de Unicidade UUID:** Uma tabela relacional estrita (`lancamentos_financeiros`) onde operações são exclusivamente `INSERT`, com uma restrição `UNIQUE(referencia)` (UUID gerado na origem do pagamento/evento).

---

## 4. Decisão Tomada e Justificativa (Decision Outcome)

Decidimos adotar a **Opção 3: Ledger Financeiro Relacional Append-Only** ([`V1__Schema_Inicial.sql`](../../apps/inv-service/src/main/resources/db/migration/V1__Schema_Inicial.sql#L59-L68)).

### Esquema Físico do Ledger
```sql
CREATE TABLE lancamentos_financeiros (
    id BIGSERIAL PRIMARY KEY,
    venda_id BIGINT NOT NULL,
    referencia UUID NOT NULL,
    tipo VARCHAR(20) NOT NULL, -- CREDITO, DEBITO, ESTORNO
    valor NUMERIC(10, 2) NOT NULL,
    data_hora TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_lancamento_venda FOREIGN KEY (venda_id) REFERENCES vendas(id),
    CONSTRAINT uk_lancamento_referencia UNIQUE (referencia)
);
```

### Regras Arquiteturais do Ledger
1. **Zero Updates / Zero Deletes:** Nenhuma instrução `UPDATE` ou `DELETE` é permitida na tabela `lancamentos_financeiros`. Estornos e correções são representados por novos lançamentos com sinal inverso ou `TipoLancamento.ESTORNO`.
2. **Idempotência por Chave Natural (UUID) e `ON CONFLICT DO NOTHING`:** A coluna `referencia` armazena o UUID originado na intenção de pagamento ou na chave determinística de estorno (`ESTORNO:<vendaId>`). Se o gateway ou a fila AMQP reenviar a mensagem, a inserção utiliza `INSERT ... ON CONFLICT (referencia) DO NOTHING RETURNING id`, absorvendo a duplicata sem abortar a transação e sem duplicar o valor contábil.

### Consequências e Trade-offs

* **Positivas (+):**
  * Trilha de auditoria 100% confiável e reproduzível matematicamente.
  * Inserções de alta velocidade sem colisão de locks entre caixas diferentes.
  * Idempotência garantida pela engine do PostgreSQL sem penalidade de `rollback-only`.
* **Negativas / Desafios (-):**
  * O cálculo de saldo em tempo real por `SUM(valor)` pode se tornar custoso à medida que a tabela cresce em milhões de linhas (resolvido via **CQRS Projections** no [ADR-0006](0006-cqrs-asynchronous-projections-and-self-healing-retry.md)).

---

## 5. Prós e Contras das Opções Analisadas

| Abordagem | Auditabilidade | Idempotência Nativa | Concorrência de Escrita | Complexidade |
| :--- | :--- | :--- | :--- | :--- |
| **Saldo Mutável (`UPDATE`)** | Péssima (Zero histórico) | Baixa | Baixa (Locks constantes) | Baixa |
| **Event Sourcing JSON** | Excelente | Alta | Alta | Muito Alta |
| **Ledger Relacional Append-Only** | **Excelente** | **Máxima (UK UUID)** | **Altíssima (Apenas INSERT)** | **Média** |

---

## 6. Estratégia de Validação & Testes

* **Teste de Idempotência Transacional:**
  * Disparar duas requisições simultâneas para registrar o mesmo pagamento com a mesma `referencia` UUID.
  * **Critério de Aceite:** Apenas 1 lançamento é persistido no banco; a segunda requisição é tratada de forma idempotente sem duplicar o valor contábil e sem lançar `UnexpectedRollbackException`.
* **Teste de Integridade Contábil:**
  * Executar 100 vendas, 20 estornos e validar se a soma dos valores no Ledger bate exatamente com o total conciliado pelo relatório financeiro.

---

## 7. Adendo (Novembro/2026 — DEBT-ARCH-003): Inserção Idempotente com `ON CONFLICT DO NOTHING`, Gate de Eventos CQRS e Chave Determinística de Estorno

Durante a execução do plano **[DEBT-ARCH-003](../debt/DEBT-ARCH-003/README.md)** ([ADR-0012](0012-idempotent-payment-pipeline-and-single-phase-sale-reversal.md)), o mecanismo de idempotência do Ledger foi aprimorado para suportar o pipeline de estornos e eliminar falhas de concorrência com Spring Data JPA:

### A. Substituição de `save()` com `IDENTITY` por `inserirIdempotente` Nativo (Achado F1)
* **Problema:** O método `save()` do Spring Data JPA lançava `DuplicateKeyException` ao colidir na constraint `uk_lancamento_referencia`. Sob transações ativas do chamador (`@Transactional`), qualquer exceção desse tipo marca a transação JPA como `rollback-only`, provocando `UnexpectedRollbackException` no commit mesmo que o erro fosse capturado em bloco `catch`.
* **Solução:** Introdução de query nativa em `LancamentoFinanceiroRepository`:
  ```sql
  INSERT INTO lancamentos_financeiros (venda_id, referencia, tipo, valor)
  VALUES (:vendaId, :referencia, :tipo, :valor)
  ON CONFLICT (referencia) DO NOTHING
  RETURNING id;
  ```
  Isso garante que a engine do PostgreSQL descarte silenciosamente a inserção redundante, retornando `Optional.empty()` sem contaminar a transação.

### B. Gate de Publicação de `LancamentoRegistradoEvent`
* O evento interno [`LancamentoRegistradoEvent`](../../apps/inv-service/src/main/java/inv/finance/infrastructure/messaging/LancamentoRegistradoEvent.java) (consumido pelo `FinancialProjectionListener` para atualizar `financial_projection_venda` e `financial_projection_diaria`) só é emitido quando `idGerado.isPresent()`.
* Em reentregas concorrentes ou idempotentes, o evento CQRS não é disparado novamente, poupando processamento de checkpoint e retry queues.

### C. Chave Determinística para Estornos de Venda Concluída (Decisão Q6)
* Para estornos contábeis disparados pelo cancelamento de vendas concluídas, o UUID de referência é derivado deterministicamente a partir do ID da venda:
  ```java
  UUID uuidReferenciaEstorno = UUID.nameUUIDFromBytes(("ESTORNO:" + vendaId).getBytes(StandardCharsets.UTF_8));
  ```
* Isso assegura que múltiplos cancelamentos ou reentregas AMQP na fila `pdv.venda.estornada.queue` convirjam para exatamente um lançamento contábil de estorno no Ledger.

---

## 8. Referências e Links Relevantes

* [Entidade LancamentoFinanceiro](../../apps/inv-service/src/main/java/inv/finance/domain/model/LancamentoFinanceiro.java)
* [Repositório LancamentoFinanceiroRepository](../../apps/inv-service/src/main/java/inv/finance/infrastructure/persistence/LancamentoFinanceiroRepository.java)
* [ADR-0006: Projeções CQRS Assíncronas](0006-cqrs-asynchronous-projections-and-self-healing-retry.md)
* [ADR-0012: Pipeline Idempotente de Pagamento e Cancelamento em Fase Única de Venda Concluída](0012-idempotent-payment-pipeline-and-single-phase-sale-reversal.md)
* [Documentação do Domínio Finance](../domains/finance.md)
