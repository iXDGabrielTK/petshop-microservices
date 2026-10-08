package common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DateRangeTest {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    @DisplayName("DateRange.hoje(Clock) deve calcular início às 00:00 e fim no início do dia seguinte conforme o fuso")
    void deveCalcularHojeComClockFixo() {
        // 2026-10-05 às 23:30 em UTC é 2026-10-05 às 20:30 em São Paulo
        Instant instant = Instant.parse("2026-10-05T23:30:00Z");
        Clock clockSP = Clock.fixed(instant, SAO_PAULO);

        DateRange range = DateRange.hoje(clockSP);

        assertThat(range.inicio()).isEqualTo(LocalDateTime.of(2026, 10, 5, 0, 0, 0));
        assertThat(range.fim()).isEqualTo(LocalDateTime.of(2026, 10, 6, 0, 0, 0));
    }

    @Test
    @DisplayName("DateRange.ontem(Clock) deve retornar o dia anterior completo")
    void deveCalcularOntemComClockFixo() {
        Instant instant = Instant.parse("2026-10-05T12:00:00Z");
        Clock clockSP = Clock.fixed(instant, SAO_PAULO);

        DateRange range = DateRange.ontem(clockSP);

        assertThat(range.inicio()).isEqualTo(LocalDateTime.of(2026, 10, 4, 0, 0, 0));
        assertThat(range.fim()).isEqualTo(LocalDateTime.of(2026, 10, 5, 0, 0, 0));
    }

    @Test
    @DisplayName("DateRange.mesAtual(Clock) deve cobrir do dia 1 até o primeiro dia do mês seguinte")
    void deveCalcularMesAtualComClockFixo() {
        Instant instant = Instant.parse("2026-10-15T10:00:00Z");
        Clock clockSP = Clock.fixed(instant, SAO_PAULO);

        DateRange range = DateRange.mesAtual(clockSP);

        assertThat(range.inicio()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0, 0));
        assertThat(range.fim()).isEqualTo(LocalDateTime.of(2026, 11, 1, 0, 0, 0));
    }

    @Test
    @DisplayName("DateRange.mesPassado(Clock) deve cobrir o mês anterior completo")
    void deveCalcularMesPassadoComClockFixo() {
        Instant instant = Instant.parse("2026-01-10T10:00:00Z"); // Janeiro de 2026 -> Mês passado: Dezembro de 2025
        Clock clockSP = Clock.fixed(instant, SAO_PAULO);

        DateRange range = DateRange.mesPassado(clockSP);

        assertThat(range.inicio()).isEqualTo(LocalDateTime.of(2025, 12, 1, 0, 0, 0));
        assertThat(range.fim()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0, 0));
    }

    @Test
    @DisplayName("DateRange.ultimosDias(N, Clock) deve retornar a janela de N dias até o fim de hoje")
    void deveCalcularUltimosDiasComClockFixo() {
        Instant instant = Instant.parse("2026-10-07T12:00:00Z");
        Clock clockSP = Clock.fixed(instant, SAO_PAULO);

        DateRange range = DateRange.ultimosDias(7, clockSP);

        assertThat(range.fim()).isEqualTo(LocalDateTime.of(2026, 10, 8, 0, 0, 0));
        assertThat(range.inicio()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0, 0));
    }

    @Test
    @DisplayName("DateRange deve lançar exceção para argumentos inválidos")
    void deveValidarArgumentosInvalidos() {
        Clock clockSP = Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), SAO_PAULO);

        assertThatThrownBy(() -> DateRange.ultimosDias(0, clockSP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dias deve ser maior que zero");

        assertThatThrownBy(() -> new DateRange(null, LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inicio e fim não podem ser null");

        assertThatThrownBy(() -> new DateRange(LocalDateTime.of(2026, 10, 5, 10, 0), LocalDateTime.of(2026, 10, 5, 9, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inicio deve ser antes de fim");
    }
}
