package inv.finance.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

public record ReceitaDiariaView(LocalDate data, BigDecimal creditos, BigDecimal debitos) {
    public ReceitaDiariaView {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(creditos, "creditos");
        Objects.requireNonNull(debitos, "debitos");
    }

    public BigDecimal liquida() {
        return creditos.subtract(debitos);
    }
}
