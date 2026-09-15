# 📦 Bounded Context: Inventory (Controle de Estoque)

Este pacote gerencia o **Catálogo de Produtos**, **Reservas e Baixas Atômicas de Estoque** (Zero-Overselling via SQL Atômico) e a emissão de alertas de estoque baixo.

Para a documentação arquitetural completa, consulte:
📖 **[Documentação de Domínio do Inventory](../../../../../../../docs/domains/inventory.md)**

### ADRs Relacionados:
* **[ADR-0002: Controle Híbrido de Concorrência e Estoque Atômico](../../../../../../../docs/adr/0002-atomic-sql-inventory-and-hybrid-locking.md)**
* **[ADR-0004: Transactional Outbox Pattern com SKIP LOCKED](../../../../../../../docs/adr/0004-transactional-outbox-with-skip-locked.md)**
