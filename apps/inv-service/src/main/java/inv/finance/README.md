# 💰 Bounded Context: Finance (Motor Contábil)

Este pacote contém o **Ledger Financeiro Append-Only**, Projeções Assíncronas CQRS (por venda e diária), fila de auto-cura para retries, o motor de Fechamento de Caixa Diário D+0 com High Watermark e o contrato público de leitura em `inv.finance.api` (`ReceitaQueryService`).

Para a documentação arquitetural completa, consulte:
📖 **[Documentação de Domínio do Finance](../../../../../../../docs/domains/finance.md)**

### ADRs Relacionados:
* **[ADR-0005: Ledger Financeiro Imutável (Append-Only)](../../../../../../../docs/adr/0005-append-only-financial-ledger-and-idempotency.md)**
* **[ADR-0006: Projeções CQRS Assíncronas e Auto-Cura](../../../../../../../docs/adr/0006-cqrs-asynchronous-projections-and-self-healing-retry.md)**
* **[ADR-0007: Fechamento de Caixa Diário D+0 com High Watermark](../../../../../../../docs/adr/0007-d0-daily-cash-closing-and-high-watermark.md)**
* **[ADR-0010: Dashboard como Módulo de Composição de Leitura](../../../../../../../docs/adr/0010-dashboard-read-only-composition-module.md)**
