package inv.dashboard.infrastructure.legacy;

import inv.dashboard.infrastructure.web.dto.DashboardStats;
import inv.dashboard.infrastructure.web.dto.DashboardStats.ChartDataDTO;
import inv.dashboard.infrastructure.web.dto.DashboardStats.RecentSaleDTO;
import inv.finance.usecase.DashboardService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Adaptador transitório para delegação ao serviço legado de Dashboard (DEBT-ARCH-001 / Etapa E4).
 * <p>
 * Package-private por isolamento conceitual. Confinado em pacote de infraestrutura legado.
 * Será descomissionado e removido na Etapa E6.
 */
@Component
public class LegacyDashboardStatsAdapter {

    private final DashboardService legacyService;

    public LegacyDashboardStatsAdapter(DashboardService legacyService) {
        this.legacyService = Objects.requireNonNull(legacyService, "legacyService");
    }

    public DashboardStats getStats() {
        inv.finance.infrastructure.web.dto.DashboardStats legacy = legacyService.getStats();
        return toDto(legacy);
    }

    public DashboardStats toDto(inv.finance.infrastructure.web.dto.DashboardStats legacy) {
        if (legacy == null) {
            return null;
        }

        List<RecentSaleDTO> recentSales = legacy.recentSales() != null
                ? legacy.recentSales().stream()
                .map(r -> new RecentSaleDTO(r.id(), r.productName(), r.amount(), r.time()))
                .toList()
                : List.of();

        List<ChartDataDTO> chartData = legacy.chartData() != null
                ? legacy.chartData().stream()
                .map(c -> new ChartDataDTO(c.name(), c.total()))
                .toList()
                : List.of();

        return new DashboardStats(
                legacy.totalRevenue(),
                legacy.revenueGrowth(),
                legacy.salesCount(),
                legacy.lowStockCount(),
                legacy.activityCount(),
                recentSales,
                chartData,
                null
        );
    }
}
