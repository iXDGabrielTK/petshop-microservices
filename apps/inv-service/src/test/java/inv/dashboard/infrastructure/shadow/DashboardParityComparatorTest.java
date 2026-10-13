package inv.dashboard.infrastructure.shadow;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import inv.dashboard.infrastructure.web.dto.DashboardStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardParityComparatorTest {

    private SimpleMeterRegistry meterRegistry;
    private DashboardParityComparator comparator;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        comparator = new DashboardParityComparator(meterRegistry);
    }

    @Test
    @DisplayName("Deve acusar delta zero e igualdade não financeira quando dados forem idênticos")
    void deveApresentarParidadeExataQuandoDadosForemIdenticos() {
        DashboardStats legacy = new DashboardStats(
                new BigDecimal("1000.00"), new BigDecimal("10.00"), 5L, 2L, 20L,
                List.of(new DashboardStats.RecentSaleDTO("1", "Produto A", new BigDecimal("100.00"), "14:00")),
                List.of(new DashboardStats.ChartDataDTO("seg.", new BigDecimal("500.00")))
        );

        DashboardStats modular = new DashboardStats(
                new BigDecimal("1000.00"), new BigDecimal("10.00"), 5L, 2L, 20L,
                List.of(new DashboardStats.RecentSaleDTO("1", "Produto A", new BigDecimal("100.00"), "14:00")),
                List.of(new DashboardStats.ChartDataDTO("seg.", new BigDecimal("500.00")))
        );

        ParityComparisonResult result = comparator.compareAndEmitParity(legacy, modular);

        assertThat(result.hasAnyDelta()).isFalse();
        assertThat(result.nonFinancialEqual()).isTrue();
        assertThat(result.totalRevenueDelta()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.revenueGrowthDelta()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.chartTotalDelta()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.chartDaysDelta()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Deve detectar divergência não financeira quando a contagem de vendas diferir")
    void deveDetectarDivergenciaNaoFinanceiraEmVendas() {
        DashboardStats legacy = new DashboardStats(
                new BigDecimal("1000.00"), BigDecimal.ZERO, 6L, 2L, 20L, List.of(), List.of()
        );
        DashboardStats modular = new DashboardStats(
                new BigDecimal("1000.00"), BigDecimal.ZERO, 5L, 2L, 20L, List.of(), List.of()
        );

        ParityComparisonResult result = comparator.compareAndEmitParity(legacy, modular);

        assertThat(result.hasAnyDelta()).isTrue();
        assertThat(result.nonFinancialEqual()).isFalse();
        assertThat(result.salesCountDelta()).isEqualTo(1L);

        assertThat(meterRegistry.find("dashboard.parity.delta").tag("campo", "salesCount").summary().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Deve auditar divergência financeira esperada em receita total e gráfico")
    void deveAuditarDivergenciaFinanceiraEsperada() {
        DashboardStats legacy = new DashboardStats(
                new BigDecimal("1000.00"), new BigDecimal("15.00"), 5L, 2L, 20L, List.of(),
                List.of(new DashboardStats.ChartDataDTO("seg.", new BigDecimal("1000.00")))
        );
        DashboardStats modular = new DashboardStats(
                new BigDecimal("950.00"), new BigDecimal("10.00"), 5L, 2L, 20L, List.of(),
                List.of(new DashboardStats.ChartDataDTO("seg.", new BigDecimal("950.00")))
        );

        ParityComparisonResult result = comparator.compareAndEmitParity(legacy, modular);

        assertThat(result.hasAnyDelta()).isTrue();
        // Métricas não financeiras continuam iguais
        assertThat(result.nonFinancialEqual()).isTrue();
        assertThat(result.totalRevenueDelta()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(result.revenueGrowthDelta()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(result.chartTotalDelta()).isEqualByComparingTo(new BigDecimal("50.00"));

        assertThat(meterRegistry.find("dashboard.parity.delta").tag("campo", "totalRevenue").summary().count()).isEqualTo(1);
        assertThat(meterRegistry.find("dashboard.parity.delta").tag("campo", "chartTotal").summary().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Deve lidar com campos nulos de forma segura sem lançar NullPointerException")
    void deveLidarComCamposNulosSemExcecao() {
        DashboardStats legacy = new DashboardStats(null, null, 0L, 0L, 0L, null, null);
        DashboardStats modular = new DashboardStats(null, null, 0L, 0L, 0L, null, null);

        ParityComparisonResult result = comparator.compareAndEmitParity(legacy, modular);

        assertThat(result.hasAnyDelta()).isFalse();
        assertThat(result.nonFinancialEqual()).isTrue();
        assertThat(result.chartTotalDelta()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
