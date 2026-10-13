package inv.dashboard.infrastructure.legacy;

import inv.dashboard.infrastructure.web.dto.DashboardStats;
import inv.finance.usecase.DashboardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LegacyDashboardStatsAdapterTest {

    @Mock
    private DashboardService legacyService;

    @Test
    @DisplayName("Deve delegar ao DashboardService legado e converter DTO corretamente")
    void deveDelegarAoLegacyServiceEConverter() {
        LegacyDashboardStatsAdapter adapter = new LegacyDashboardStatsAdapter(legacyService);

        inv.finance.infrastructure.web.dto.DashboardStats legacy = new inv.finance.infrastructure.web.dto.DashboardStats(
                new BigDecimal("500.00"), new BigDecimal("15.00"), 10L, 2L, 50L,
                List.of(new inv.finance.infrastructure.web.dto.DashboardStats.RecentSaleDTO("1", "Ração Especial", new BigDecimal("100.00"), "14:00")),
                List.of(new inv.finance.infrastructure.web.dto.DashboardStats.ChartDataDTO("seg.", new BigDecimal("500.00")))
        );

        when(legacyService.getStats()).thenReturn(legacy);

        DashboardStats stats = adapter.getStats();

        assertThat(stats).isNotNull();
        assertThat(stats.totalRevenue()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(stats.revenueGrowth()).isEqualByComparingTo(new BigDecimal("15.00"));
        assertThat(stats.salesCount()).isEqualTo(10L);
        assertThat(stats.lowStockCount()).isEqualTo(2L);
        assertThat(stats.activityCount()).isEqualTo(50L);
        assertThat(stats.recentSales()).hasSize(1);
        assertThat(stats.recentSales().getFirst().productName()).isEqualTo("Ração Especial");
        assertThat(stats.chartData()).hasSize(1);
        assertThat(stats.meta()).isNull();

        verify(legacyService).getStats();
    }

    @Test
    @DisplayName("toDto deve retornar null quando legacy for nulo")
    void deveRetornarNullQuandoLegacyForNulo() {
        LegacyDashboardStatsAdapter adapter = new LegacyDashboardStatsAdapter(legacyService);

        assertThat(adapter.toDto(null)).isNull();
    }
}
