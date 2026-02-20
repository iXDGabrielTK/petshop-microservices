package common.util;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record DateRange(
        LocalDateTime inicio,
        LocalDateTime fim
) {

    public DateRange {
        if (inicio == null || fim == null) {
            throw new IllegalArgumentException("inicio e fim não podem ser null");
        }

        if (!inicio.isBefore(fim)) {
            throw new IllegalArgumentException("inicio deve ser antes de fim");
        }
    }

    public static DateRange hoje() {
        LocalDateTime inicio = LocalDate.now().atStartOfDay();
        return new DateRange(inicio, inicio.plusDays(1));
    }

    public static DateRange ontem() {
        LocalDateTime inicio = LocalDate.now().minusDays(1).atStartOfDay();
        return new DateRange(inicio, inicio.plusDays(1));
    }

    public static DateRange mesAtual() {
        LocalDateTime inicio = LocalDate.now()
                .withDayOfMonth(1)
                .atStartOfDay();

        return new DateRange(inicio, inicio.plusMonths(1));
    }

    public static DateRange mesPassado() {
        LocalDateTime inicio = LocalDate.now()
                .minusMonths(1)
                .withDayOfMonth(1)
                .atStartOfDay();

        return new DateRange(inicio, inicio.plusMonths(1));
    }

    public static DateRange ultimosDias(int dias) {
        if (dias <= 0) {
            throw new IllegalArgumentException("dias deve ser maior que zero");
        }

        LocalDateTime fim = LocalDate.now()
                .plusDays(1)
                .atStartOfDay();

        LocalDateTime inicio = fim.minusDays(dias);

        return new DateRange(inicio, fim);
    }

}
