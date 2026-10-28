# 🛒 Bounded Context: Checkout (Vendas e Pagamentos)

Este pacote contém o agregado de **Vendas**, máquinas de estados, integração com Webhooks de Pagamento, suporte a Long Polling para terminais de PDV e contrato público de leitura em `inv.checkout.api` (`VendaQueryService`).

Para a documentação arquitetural completa, consulte:
📖 **[Documentação de Domínio do Checkout](../../../../../../../docs/domains/checkout.md)**

### ADRs Relacionados:
* **[ADR-0002: Controle Híbrido de Concorrência](../../../../../../../docs/adr/0002-atomic-sql-inventory-and-hybrid-locking.md)**
* **[ADR-0003: Long Polling Não-Bloqueante com DeferredResult](../../../../../../../docs/adr/0003-async-long-polling-pos-deferred-result.md)**
* **[ADR-0010: Dashboard como Módulo de Composição de Leitura](../../../../../../../docs/adr/0010-dashboard-read-only-composition-module.md)**
* **[ADR-0011: Open Host Service Síncrono de Comandos e Catálogo (Checkout → Inventory)](../../../../../../../docs/adr/0011-synchronous-open-host-service-checkout-inventory.md)**
