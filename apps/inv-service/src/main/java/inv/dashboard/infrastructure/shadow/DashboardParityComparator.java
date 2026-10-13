package inv.dashboard.infrastructure.shadow;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import inv.dashboard.infrastructure.web.dto.DashboardStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Comparador de paridade campo a campo entre execuções legada e modular do Dashboard.
 * Emite métricas no Micrometer ('dashboard.parity.delta') e logs estruturados de auditoria.
 */
@Component
public class DashboardParityComparator {

    private static final Logger log = LoggerFactory.getLogger(DashboardParityComparator.class);

    private final MeterRegistry meters;

    public DashboardParityComparator(MeterRegistry meters) {
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    /**
     * Compara campo a campo as estatísticas geradas pelo legado contra as geradas pelo modular.
     *
     * @param legacy dados retornados pelo fluxo legado
     * @param modular dados retornados pelo fluxo modular
     * @return resultado detalhado da reconciliação
     */
    public ParityComparisonResult compareAndEmitParity(DashboardStats legacy, DashboardStats modular) {
        Objects.requireNonNull(legacy, "legacy");
        Objects.requireNonNull(modular, "modular");

        BigDecimal totalRevenueDelta = safeSubtract(legacy.totalRevenue(), modular.totalRevenue());
        BigDecimal revenueGrowthDelta = safeSubtract(legacy.revenueGrowth(), modular.revenueGrowth());
        long salesCountDelta = legacy.salesCount() - modular.salesCount();
        long lowStockCountDelta = legacy.lowStockCount() - modular.lowStockCount();
        long activityCountDelta = legacy.activityCount() - modular.activityCount();
        long recentSalesCountDelta = (legacy.recentSales() != null ? legacy.recentSales().size() : 0)
                - (modular.recentSales() != null ? modular.recentSales().size() : 0);

        BigDecimal legacyChartTotal = legacy.chartData() != null
                ? legacy.chartData().stream().map(DashboardStats.ChartDataDTO::total).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add)
                : BigDecimal.ZERO;
        BigDecimal modularChartTotal = modular.chartData() != null
                ? modular.chartData().stream().map(DashboardStats.ChartDataDTO::total).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add)
                : BigDecimal.ZERO;
        BigDecimal chartTotalDelta = safeSubtract(legacyChartTotal, modularChartTotal);
        long chartDaysDelta = (legacy.chartData() != null ? legacy.chartData().size() : 0)
                - (modular.chartData() != null ? modular.chartData().size() : 0);

        boolean nonFinancialEqual = salesCountDelta == 0
                && lowStockCountDelta == 0
                && activityCountDelta == 0;

        recordMetric("totalRevenue", totalRevenueDelta.abs().doubleValue(), totalRevenueDelta.compareTo(BigDecimal.ZERO) != 0);
        recordMetric("revenueGrowth", revenueGrowthDelta.abs().doubleValue(), revenueGrowthDelta.compareTo(BigDecimal.ZERO) != 0);
        recordMetric("salesCount", Math.abs(salesCountDelta), salesCountDelta != 0);
        recordMetric("lowStockCount", Math.abs(lowStockCountDelta), lowStockCountDelta != 0);
        recordMetric("activityCount", Math.abs(activityCountDelta), activityCountDelta != 0);
        recordMetric("recentSales", Math.abs(recentSalesCountDelta), recentSalesCountDelta != 0);
        recordMetric("chartTotal", chartTotalDelta.abs().doubleValue(), chartTotalDelta.compareTo(BigDecimal.ZERO) != 0);
        recordMetric("chartDays", Math.abs(chartDaysDelta), chartDaysDelta != 0);

        log.info("Shadow parity audit: totalRevenue[leg={}, mod={}, delta={}], revenueGrowth[leg={}, mod={}, delta={}], salesCount[leg={}, mod={}, delta={}], lowStockCount[leg={}, mod={}, delta={}], activityCount[leg={}, mod={}, delta={}], recentSalesCount[leg={}, mod={}, delta={}], chartTotal[leg={}, mod={}, delta={}], chartDays[leg={}, mod={}, delta={}]",
                legacy.totalRevenue(), modular.totalRevenue(), totalRevenueDelta,
                legacy.revenueGrowth(), modular.revenueGrowth(), revenueGrowthDelta,
                legacy.salesCount(), modular.salesCount(), salesCountDelta,
                legacy.lowStockCount(), modular.lowStockCount(), lowStockCountDelta,
                legacy.activityCount(), modular.activityCount(), activityCountDelta,
                legacy.recentSales() != null ? legacy.recentSales().size() : 0,
                modular.recentSales() != null ? modular.recentSales().size() : 0,
                recentSalesCountDelta,
                legacyChartTotal, modularChartTotal, chartTotalDelta,
                legacy.chartData() != null ? legacy.chartData().size() : 0,
                modular.chartData() != null ? modular.chartData().size() : 0,
                chartDaysDelta);

        if (!nonFinancialEqual) {
            log.warn("Divergência detectada em métricas não financeiras no shadow mode! salesCount delta={}, lowStock delta={}, activity delta={}",
                    salesCountDelta, lowStockCountDelta, activityCountDelta);
        }

        return new ParityComparisonResult(
                totalRevenueDelta,
                revenueGrowthDelta,
                salesCountDelta,
                lowStockCountDelta,
                activityCountDelta,
                recentSalesCountDelta,
                chartTotalDelta,
                chartDaysDelta,
                nonFinancialEqual
        );
    }

    private BigDecimal safeSubtract(BigDecimal a, BigDecimal b) {
        BigDecimal valA = a != null ? a : BigDecimal.ZERO;
        BigDecimal valB = b != null ? b : BigDecimal.ZERO;
        return valA.subtract(valB);
    }

    private void recordMetric(String campo, double absDelta, boolean divergente) {
        DistributionSummary.builder("dashboard.parity.delta")
                .tag("campo", campo)
                .register(meters)
                .record(absDelta);

        meters.counter("dashboard.parity.delta.campo",
                "campo", campo,
                "divergente", divergente ? "true" : "false"
        ).increment();
    }
}
