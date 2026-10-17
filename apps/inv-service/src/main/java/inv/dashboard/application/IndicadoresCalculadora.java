package inv.dashboard.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Funções puras para cálculo de métricas e indicadores do Dashboard.
 */
public final class IndicadoresCalculadora {

    private IndicadoresCalculadora() {}

    /**
     * Calcula o percentual de crescimento entre o valor atual e o período anterior de referência.
     * <p>
     * Decisão Q2: Quando o período anterior for zero ou nulo, retorna 0 (crescimento neutro),
     * evitando divisão por zero.
     *
     * @param atual valor do período atual (se nulo, tratado como zero)
     * @param passado valor do período anterior de referência
     * @return percentual de crescimento com 4 casas decimais multiplicado por 100
     */
    public static BigDecimal crescimentoPercentual(BigDecimal atual, BigDecimal passado) {
        if (passado == null || passado.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal valorAtual = atual != null ? atual : BigDecimal.ZERO;

        return valorAtual.subtract(passado)
                .divide(passado.abs(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }
}
