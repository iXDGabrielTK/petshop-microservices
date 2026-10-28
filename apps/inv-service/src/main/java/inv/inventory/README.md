# 📦 Bounded Context: Inventory (Controle de Estoque)

Este pacote gerencia o **Catálogo de Produtos**, **Reservas e Baixas Atômicas de Estoque em Lote Ordenado** (Zero-Overselling e Zero-Deadlock via SQL Atômico), emissão de alertas de estoque baixo e a *Published Language* em `inv.inventory.api` (`EstoqueCommandService`, `CatalogoQueryService`, `EstoqueQueryService`).

Para a documentação arquitetural completa, consulte:
📖 **[Documentação de Domínio do Inventory](../../../../../../../docs/domains/inventory.md)**

### ADRs Relacionados:
* **[ADR-0002: Controle Híbrido de Concorrência e Estoque Atômico](../../../../../../../docs/adr/0002-atomic-sql-inventory-and-hybrid-locking.md)**
* **[ADR-0004: Transactional Outbox Pattern com SKIP LOCKED](../../../../../../../docs/adr/0004-transactional-outbox-with-skip-locked.md)**
* **[ADR-0010: Dashboard como Módulo de Composição de Leitura](../../../../../../../docs/adr/0010-dashboard-read-only-composition-module.md)**
* **[ADR-0011: Open Host Service Síncrono de Comandos e Catálogo (Checkout → Inventory)](../../../../../../../docs/adr/0011-synchronous-open-host-service-checkout-inventory.md)**
