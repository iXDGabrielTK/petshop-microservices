package inv.inventory.api;

import java.math.BigDecimal;
import java.util.Objects;

public record ItemComando(long produtoId, BigDecimal quantidade) {
    public ItemComando {
        if (produtoId <= 0) throw new IllegalArgumentException("produtoId deve ser positivo");
        Objects.requireNonNull(quantidade, "quantidade");
        if (quantidade.signum() <= 0) throw new IllegalArgumentException("quantidade deve ser positiva");
    }
}
