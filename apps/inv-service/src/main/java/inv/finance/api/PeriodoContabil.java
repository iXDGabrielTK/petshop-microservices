package inv.finance.api;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public record PeriodoContabil(LocalDate inicio, LocalDate fim) {
    private static final long MAX_DIAS = 400;

    public PeriodoContabil {
        Objects.requireNonNull(inicio, "inicio");
        Objects.requireNonNull(fim, "fim");
        if (fim.isBefore(inicio)) {
            throw new IllegalArgumentException("fim < inicio");
        }
        if (ChronoUnit.DAYS.between(inicio, fim) > MAX_DIAS) {
            throw new IllegalArgumentException("período excede " + MAX_DIAS + " dias");
        }
    }

    public static PeriodoContabil dia(LocalDate d) {
        return new PeriodoContabil(d, d);
    }

    public static PeriodoContabil mes(YearMonth m) {
        return new PeriodoContabil(m.atDay(1), m.atEndOfMonth());
    }
}
