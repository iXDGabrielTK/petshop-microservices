package inv.inventory.api;

import java.math.BigDecimal;
import java.util.Objects;

public record ProdutoCheckoutView(Long id, String nome, BigDecimal precoVenda) {
    public ProdutoCheckoutView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nome, "nome");
        Objects.requireNonNull(precoVenda, "precoVenda");
    }
}
