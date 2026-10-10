package inv.checkout.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

public record FaturamentoDiarioView(LocalDate data, BigDecimal total) {
    public FaturamentoDiarioView {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(total, "total");
    }
}
