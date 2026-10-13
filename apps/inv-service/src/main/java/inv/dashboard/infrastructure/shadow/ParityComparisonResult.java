package inv.dashboard.infrastructure.shadow;

import java.math.BigDecimal;

/**
 * Resultado detalhado da auditoria de paridade campo a campo entre as engines legacy e modular.
 */
public record ParityComparisonResult(
        BigDecimal totalRevenueDelta,
        BigDecimal revenueGrowthDelta,
        long salesCountDelta,
        long lowStockCountDelta,
        long activityCountDelta,
        long recentSalesCountDelta,
        BigDecimal chartTotalDelta,
        long chartDaysDelta,
        boolean nonFinancialEqual
) {
    public ParityComparisonResult(
            BigDecimal totalRevenueDelta,
            BigDecimal revenueGrowthDelta,
            long salesCountDelta,
            long lowStockCountDelta,
            long activityCountDelta,
            long recentSalesCountDelta,
            boolean nonFinancialEqual
    ) {
        this(totalRevenueDelta, revenueGrowthDelta, salesCountDelta, lowStockCountDelta, activityCountDelta, recentSalesCountDelta, BigDecimal.ZERO, 0L, nonFinancialEqual);
    }

    public boolean hasAnyDelta() {
        return (totalRevenueDelta != null && totalRevenueDelta.compareTo(BigDecimal.ZERO) != 0)
                || (revenueGrowthDelta != null && revenueGrowthDelta.compareTo(BigDecimal.ZERO) != 0)
                || salesCountDelta != 0
                || lowStockCountDelta != 0
                || activityCountDelta != 0
                || recentSalesCountDelta != 0
                || (chartTotalDelta != null && chartTotalDelta.compareTo(BigDecimal.ZERO) != 0)
                || chartDaysDelta != 0;
    }
}
