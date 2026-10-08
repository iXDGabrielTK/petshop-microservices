package inv.dashboard;

import inv.finance.infrastructure.web.dto.DashboardStats;
import inv.finance.infrastructure.web.dto.DashboardStats.ChartDataDTO;
import inv.finance.infrastructure.web.dto.DashboardStats.RecentSaleDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.autoconfigure.json.JsonTestersAutoConfiguration;
import org.springframework.boot.test.json.JacksonTester;
import org.springframework.boot.test.json.JsonContent;
import org.springframework.test.context.ContextConfiguration;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de contrato congelando os nomes e tipos dos campos do JSON retornado por /dashboard/stats.
 * Garante que nenhuma refatoração subsequente (E1 a E6 do DEBT-ARCH-001) altere o contrato consumido pelo frontend.
 */
@JsonTest
@ContextConfiguration(classes = {JacksonAutoConfiguration.class, JsonTestersAutoConfiguration.class})
class DashboardStatsContractTest {

    @Autowired
    private JacksonTester<DashboardStats> json;

    @Test
    @DisplayName("Deve serializar DashboardStats garantindo conformidade estrita com os tipos e chaves do contrato frontend")
    void deveSerializarComContratoExatoJson() throws IOException {
        DashboardStats stats = new DashboardStats(
                new BigDecimal("12500.50"),
                new BigDecimal("15.25"),
                42L,
                5L,
                120L,
                List.of(
                        new RecentSaleDTO("1", "Ração Golden Especial 15kg", new BigDecimal("189.90"), "14:32"),
                        new RecentSaleDTO("2", "Brinquedo Mordedor + outros", new BigDecimal("45.00"), "14:15")
                ),
                List.of(
                        new ChartDataDTO("seg.", new BigDecimal("1500.00")),
                        new ChartDataDTO("ter.", new BigDecimal("2300.00"))
                )
        );

        JsonContent<DashboardStats> result = json.write(stats);

        // Validação estrita das chaves de primeiro nível
        assertThat(result).hasJsonPath("$.totalRevenue");
        assertThat(result).hasJsonPath("$.revenueGrowth");
        assertThat(result).hasJsonPath("$.salesCount");
        assertThat(result).hasJsonPath("$.lowStockCount");
        assertThat(result).hasJsonPath("$.activityCount");
        assertThat(result).hasJsonPath("$.recentSales");
        assertThat(result).hasJsonPath("$.chartData");

        // Validação de tipos e valores
        assertThat(result).extractingJsonPathNumberValue("$.totalRevenue").isEqualTo(12500.50);
        assertThat(result).extractingJsonPathNumberValue("$.revenueGrowth").isEqualTo(15.25);
        assertThat(result).extractingJsonPathNumberValue("$.salesCount").isEqualTo(42);
        assertThat(result).extractingJsonPathNumberValue("$.lowStockCount").isEqualTo(5);
        assertThat(result).extractingJsonPathNumberValue("$.activityCount").isEqualTo(120);

        // Validação de recentSales (array de RecentSaleDTO)
        assertThat(result).extractingJsonPathArrayValue("$.recentSales").hasSize(2);
        assertThat(result).extractingJsonPathStringValue("$.recentSales[0].id").isEqualTo("1");
        assertThat(result).extractingJsonPathStringValue("$.recentSales[0].productName").isEqualTo("Ração Golden Especial 15kg");
        assertThat(result).extractingJsonPathNumberValue("$.recentSales[0].amount").isEqualTo(189.90);
        assertThat(result).extractingJsonPathStringValue("$.recentSales[0].time").isEqualTo("14:32");

        assertThat(result).extractingJsonPathStringValue("$.recentSales[1].id").isEqualTo("2");
        assertThat(result).extractingJsonPathStringValue("$.recentSales[1].productName").isEqualTo("Brinquedo Mordedor + outros");
        assertThat(result).extractingJsonPathNumberValue("$.recentSales[1].amount").isEqualTo(45.00);
        assertThat(result).extractingJsonPathStringValue("$.recentSales[1].time").isEqualTo("14:15");

        // Validação de chartData (array de ChartDataDTO)
        assertThat(result).extractingJsonPathArrayValue("$.chartData").hasSize(2);
        assertThat(result).extractingJsonPathStringValue("$.chartData[0].name").isEqualTo("seg.");
        assertThat(result).extractingJsonPathNumberValue("$.chartData[0].total").isEqualTo(1500.00);
        assertThat(result).extractingJsonPathStringValue("$.chartData[1].name").isEqualTo("ter.");
        assertThat(result).extractingJsonPathNumberValue("$.chartData[1].total").isEqualTo(2300.00);
        // Validação estrita contra contrato JSON congelado (Golden Master do contrato)
        assertThat(result).isEqualToJson("""
                {
                    "totalRevenue": 12500.50,
                    "revenueGrowth": 15.25,
                    "salesCount": 42,
                    "lowStockCount": 5,
                    "activityCount": 120,
                    "recentSales": [
                        {
                            "id": "1",
                            "productName": "Ração Golden Especial 15kg",
                            "amount": 189.90,
                            "time": "14:32"
                        },
                        {
                            "id": "2",
                            "productName": "Brinquedo Mordedor + outros",
                            "amount": 45.00,
                            "time": "14:15"
                        }
                    ],
                    "chartData": [
                        {
                            "name": "seg.",
                            "total": 1500.00
                        },
                        {
                            "name": "ter.",
                            "total": 2300.00
                        }
                    ]
                }
                """);
    }

    @Test
    @DisplayName("Deve desserializar JSON canônico para DashboardStats mantendo compatibilidade de tipos")
    void deveDeserializarJsonParaDashboardStatsMantendoTipos() throws IOException {
        String canonicalJson = """
                {
                    "totalRevenue": 12500.50,
                    "revenueGrowth": 15.25,
                    "salesCount": 42,
                    "lowStockCount": 5,
                    "activityCount": 120,
                    "recentSales": [
                        {
                            "id": "1",
                            "productName": "Ração Golden Especial 15kg",
                            "amount": 189.90,
                            "time": "14:32"
                        }
                    ],
                    "chartData": [
                        {
                            "name": "seg.",
                            "total": 1500.00
                        }
                    ]
                }
                """;

        DashboardStats parsed = json.parseObject(canonicalJson);

        assertThat(parsed).isNotNull();
        assertThat(parsed.totalRevenue()).isEqualByComparingTo("12500.50");
        assertThat(parsed.revenueGrowth()).isEqualByComparingTo("15.25");
        assertThat(parsed.salesCount()).isEqualTo(42L);
        assertThat(parsed.lowStockCount()).isEqualTo(5L);
        assertThat(parsed.activityCount()).isEqualTo(120L);

        assertThat(parsed.recentSales()).hasSize(1);
        RecentSaleDTO sale = parsed.recentSales().getFirst();
        assertThat(sale.id()).isEqualTo("1");
        assertThat(sale.productName()).isEqualTo("Ração Golden Especial 15kg");
        assertThat(sale.amount()).isEqualByComparingTo("189.90");
        assertThat(sale.time()).isEqualTo("14:32");

        assertThat(parsed.chartData()).hasSize(1);
        ChartDataDTO chart = parsed.chartData().getFirst();
        assertThat(chart.name()).isEqualTo("seg.");
        assertThat(chart.total()).isEqualByComparingTo("1500.00");
    }

    @Test
    @DisplayName("Deve serializar corretamente quando listas estiverem vazias e valores zerados")
    void deveSerializarDashboardComListasVaziasEZeros() throws IOException {
        DashboardStats stats = new DashboardStats(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0L,
                0L,
                0L,
                List.of(),
                List.of()
        );

        JsonContent<DashboardStats> result = json.write(stats);

        assertThat(result).extractingJsonPathNumberValue("$.totalRevenue").isEqualTo(0);
        assertThat(result).extractingJsonPathNumberValue("$.revenueGrowth").isEqualTo(0);
        assertThat(result).extractingJsonPathNumberValue("$.salesCount").isEqualTo(0);
        assertThat(result).extractingJsonPathNumberValue("$.lowStockCount").isEqualTo(0);
        assertThat(result).extractingJsonPathNumberValue("$.activityCount").isEqualTo(0);
        assertThat(result).extractingJsonPathArrayValue("$.recentSales").isEmpty();
        assertThat(result).extractingJsonPathArrayValue("$.chartData").isEmpty();

        assertThat(result).isEqualToJson("""
                {
                    "totalRevenue": 0,
                    "revenueGrowth": 0,
                    "salesCount": 0,
                    "lowStockCount": 0,
                    "activityCount": 0,
                    "recentSales": [],
                    "chartData": []
                }
                """);
    }

    @Test
    @DisplayName("Deve congelar o comportamento de serialização com campos nulos")
    void deveCongelarComportamentoDeCamposNulos() throws IOException {
        DashboardStats stats = new DashboardStats(
                null,
                null,
                0L,
                0L,
                0L,
                null,
                null
        );

        JsonContent<DashboardStats> result = json.write(stats);

        assertThat(result).isEqualToJson("""
                {
                    "totalRevenue": null,
                    "revenueGrowth": null,
                    "salesCount": 0,
                    "lowStockCount": 0,
                    "activityCount": 0,
                    "recentSales": null,
                    "chartData": null
                }
                """);
    }
}
