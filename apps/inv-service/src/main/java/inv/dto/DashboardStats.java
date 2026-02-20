package inv.dto;

import java.math.BigDecimal;
import java.util.List;

public record DashboardStats(
        BigDecimal totalRevenue,
        BigDecimal revenueGrowth,
        long salesCount,
        long lowStockCount,
        long activityCount,
        List<RecentSaleDTO> recentSales,
        List<ChartDataDTO> chartData
) {
    public record RecentSaleDTO(String id, String productName, BigDecimal amount, String time) {}
    public record ChartDataDTO(String name, BigDecimal total) {}
}