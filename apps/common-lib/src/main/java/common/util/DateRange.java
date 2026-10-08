package common.util;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

public record DateRange(
        LocalDateTime inicio,
        LocalDateTime fim
) {

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Sao_Paulo");

    public DateRange {
        if (inicio == null || fim == null) {
            throw new IllegalArgumentException("inicio e fim não podem ser null");
        }

        if (!inicio.isBefore(fim)) {
            throw new IllegalArgumentException("inicio deve ser antes de fim");
        }
    }

    public static DateRange hoje() {
        return hoje(Clock.system(DEFAULT_ZONE));
    }

    public static DateRange hoje(Clock clock) {
        LocalDateTime inicio = LocalDate.now(clock).atStartOfDay();
        return new DateRange(inicio, inicio.plusDays(1));
    }

    public static DateRange ontem() {
        return ontem(Clock.system(DEFAULT_ZONE));
    }

    public static DateRange ontem(Clock clock) {
        LocalDateTime inicio = LocalDate.now(clock).minusDays(1).atStartOfDay();
        return new DateRange(inicio, inicio.plusDays(1));
    }

    public static DateRange mesAtual() {
        return mesAtual(Clock.system(DEFAULT_ZONE));
    }

    public static DateRange mesAtual(Clock clock) {
        LocalDateTime inicio = LocalDate.now(clock)
                .withDayOfMonth(1)
                .atStartOfDay();
        return new DateRange(inicio, inicio.plusMonths(1));
    }

    public static DateRange mesPassado() {
        return mesPassado(Clock.system(DEFAULT_ZONE));
    }

    public static DateRange mesPassado(Clock clock) {
        LocalDateTime inicio = LocalDate.now(clock)
                .minusMonths(1)
                .withDayOfMonth(1)
                .atStartOfDay();
        return new DateRange(inicio, inicio.plusMonths(1));
    }

    public static DateRange ultimosDias(int dias) {
        return ultimosDias(dias, Clock.system(DEFAULT_ZONE));
    }

    public static DateRange ultimosDias(int dias, Clock clock) {
        if (dias <= 0) {
            throw new IllegalArgumentException("dias deve ser maior que zero");
        }

        LocalDateTime fim = LocalDate.now(clock)
                .plusDays(1)
                .atStartOfDay();

        LocalDateTime inicio = fim.minusDays(dias);

        return new DateRange(inicio, fim);
    }
}
