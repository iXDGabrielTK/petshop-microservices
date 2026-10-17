package inv.dashboard.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class IndicadoresCalculadoraTest {

    @Test
    @DisplayName("Deve calcular crescimento percentual positivo corretamente")
    void deveCalcularCrescimentoPositivo() {
        BigDecimal atual = new BigDecimal("150.00");
        BigDecimal passado = new BigDecimal("100.00");

        BigDecimal resultado = IndicadoresCalculadora.crescimentoPercentual(atual, passado);

        assertThat(resultado).isEqualByComparingTo(new BigDecimal("50.0000"));
    }

    @Test
    @DisplayName("Deve calcular crescimento percentual negativo corretamente")
    void deveCalcularCrescimentoNegativo() {
        BigDecimal atual = new BigDecimal("75.00");
        BigDecimal passado = new BigDecimal("100.00");

        BigDecimal resultado = IndicadoresCalculadora.crescimentoPercentual(atual, passado);

        assertThat(resultado).isEqualByComparingTo(new BigDecimal("-25.0000"));
    }

    @Test
    @DisplayName("Decisão Q2: Deve retornar zero quando o período passado for zero para evitar divisão por zero")
    void deveRetornarZeroQuandoPassadoForZero() {
        BigDecimal atual = new BigDecimal("1500.00");
        BigDecimal passado = BigDecimal.ZERO;

        BigDecimal resultado = IndicadoresCalculadora.crescimentoPercentual(atual, passado);

        assertThat(resultado).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Decisão Q2: Deve retornar zero quando o período passado for nulo")
    void deveRetornarZeroQuandoPassadoForNulo() {
        BigDecimal atual = new BigDecimal("1500.00");

        BigDecimal resultado = IndicadoresCalculadora.crescimentoPercentual(atual, null);

        assertThat(resultado).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Deve tratar atual nulo como zero e calcular decréscimo total (-100%)")
    void deveTratarAtualNuloComoZero() {
        BigDecimal passado = new BigDecimal("200.00");

        BigDecimal resultado = IndicadoresCalculadora.crescimentoPercentual(null, passado);

        assertThat(resultado).isEqualByComparingTo(new BigDecimal("-100.0000"));
    }

    @Test
    @DisplayName("Deve retornar zero quando ambos os períodos forem nulos ou zerados")
    void deveRetornarZeroQuandoAmbosForemNulosOuZerados() {
        assertThat(IndicadoresCalculadora.crescimentoPercentual(null, null)).isEqualTo(BigDecimal.ZERO);
        assertThat(IndicadoresCalculadora.crescimentoPercentual(BigDecimal.ZERO, BigDecimal.ZERO)).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Deve calcular corretamente crescimento quando a receita líquida do período passado for negativa")
    void deveCalcularCrescimentoQuandoPassadoForNegativo() {
        // Prejuízo reduziu de -100 para -50: melhoria real de +50%
        BigDecimal atual = new BigDecimal("-50.00");
        BigDecimal passado = new BigDecimal("-100.00");

        BigDecimal resultado = IndicadoresCalculadora.crescimentoPercentual(atual, passado);

        assertThat(resultado).isEqualByComparingTo(new BigDecimal("50.0000"));
    }

    @Test
    @DisplayName("Deve calcular corretamente crescimento quando passou de prejuízo (-100) para lucro (+100)")
    void deveCalcularCrescimentoQuandoPassouDePrejuizoParaLucro() {
        BigDecimal atual = new BigDecimal("100.00");
        BigDecimal passado = new BigDecimal("-100.00");

        BigDecimal resultado = IndicadoresCalculadora.crescimentoPercentual(atual, passado);

        assertThat(resultado).isEqualByComparingTo(new BigDecimal("200.0000"));
    }
}
