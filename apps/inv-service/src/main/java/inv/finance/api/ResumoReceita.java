package inv.finance.api;

import java.math.BigDecimal;
import java.util.Objects;

public record ResumoReceita(BigDecimal creditos, BigDecimal debitos) {
    public static final ResumoReceita ZERO = new ResumoReceita(BigDecimal.ZERO, BigDecimal.ZERO);

    public ResumoReceita {
        Objects.requireNonNull(creditos, "creditos");
        Objects.requireNonNull(debitos, "debitos");
    }

    public BigDecimal liquida() {
        return creditos.subtract(debitos);
    }
}
