package inv.inventory.infrastructure.persistence;

import java.math.BigDecimal;

/** Projeção interna ao Inventory: nunca sai do contexto. */
public record ProdutoMetadadosEstoque(Long id, String nome, BigDecimal estoqueMinimo) {}
