package inv.dashboard.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * DTO web retornado pelo endpoint GET /dashboard/stats.
 * Preserva compatibilidade estrita com o contrato JSON do frontend (Golden Master)
 * e adiciona o campo 'meta' opcional para observabilidade de consistência eventual.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DashboardStats(
        BigDecimal totalRevenue,
        BigDecimal revenueGrowth,
        long salesCount,
        long lowStockCount,
        long activityCount,
        List<RecentSaleDTO> recentSales,
        List<ChartDataDTO> chartData,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        MetaDTO meta
) {
    public DashboardStats(
            BigDecimal totalRevenue,
            BigDecimal revenueGrowth,
            long salesCount,
            long lowStockCount,
            long activityCount,
            List<RecentSaleDTO> recentSales,
            List<ChartDataDTO> chartData
    ) {
        this(totalRevenue, revenueGrowth, salesCount, lowStockCount, activityCount, recentSales, chartData, null);
    }

    public record RecentSaleDTO(String id, String productName, BigDecimal amount, String time) {}

    public record ChartDataDTO(String name, BigDecimal total) {}

    public record MetaDTO(
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            Instant geradoEm,
            FrescorDTO frescor,
            List<String> cardsDegradados
    ) {}

    public record FrescorDTO(
            long ultimoLancamentoLedger,
            long ultimoLancamentoProjetado,
            long pendentesRetry,
            long atraso,
            boolean emDia
    ) {}
}
