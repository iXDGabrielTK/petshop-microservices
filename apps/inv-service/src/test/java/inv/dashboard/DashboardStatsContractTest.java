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

    @Autowired
    private JacksonTester<inv.dashboard.infrastructure.web.dto.DashboardStats> modularJson;

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

    @Test
    @DisplayName("Deve serializar o DTO modular inv.dashboard.infrastructure.web.dto.DashboardStats de forma 100% idêntica ao contrato canônico quando meta for nulo")
    void deveSerializarModularDashboardStatsComMesmoContratoJsonQuandoMetaForNulo() throws IOException {
        inv.dashboard.infrastructure.web.dto.DashboardStats stats = new inv.dashboard.infrastructure.web.dto.DashboardStats(
                new BigDecimal("12500.50"),
                new BigDecimal("15.25"),
                42L,
                5L,
                120L,
                List.of(
                        new inv.dashboard.infrastructure.web.dto.DashboardStats.RecentSaleDTO("1", "Ração Golden Especial 15kg", new BigDecimal("189.90"), "14:32"),
                        new inv.dashboard.infrastructure.web.dto.DashboardStats.RecentSaleDTO("2", "Brinquedo Mordedor + outros", new BigDecimal("45.00"), "14:15")
                ),
                List.of(
                        new inv.dashboard.infrastructure.web.dto.DashboardStats.ChartDataDTO("seg.", new BigDecimal("1500.00")),
                        new inv.dashboard.infrastructure.web.dto.DashboardStats.ChartDataDTO("ter.", new BigDecimal("2300.00"))
                )
        );

        JsonContent<inv.dashboard.infrastructure.web.dto.DashboardStats> result = modularJson.write(stats);

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
    @DisplayName("Deve serializar o DTO modular com campo 'meta' aditivo mantendo retrocompatibilidade total")
    void deveSerializarModularDashboardStatsComCampoMetaAditivo() throws IOException {
        java.time.Instant geradoEm = java.time.Instant.parse("2026-10-15T10:00:00Z");
        inv.dashboard.infrastructure.web.dto.DashboardStats.MetaDTO meta = new inv.dashboard.infrastructure.web.dto.DashboardStats.MetaDTO(
                geradoEm,
                new inv.dashboard.infrastructure.web.dto.DashboardStats.FrescorDTO(100L, 100L, 0L, 0L, true),
                List.of("atividade")
        );

        inv.dashboard.infrastructure.web.dto.DashboardStats stats = new inv.dashboard.infrastructure.web.dto.DashboardStats(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0L,
                0L,
                0L,
                List.of(),
                List.of(),
                meta
        );

        JsonContent<inv.dashboard.infrastructure.web.dto.DashboardStats> result = modularJson.write(stats);

        assertThat(result).hasJsonPath("$.meta");
        assertThat(result).extractingJsonPathStringValue("$.meta.geradoEm").isEqualTo("2026-10-15T10:00:00Z");
        assertThat(result).extractingJsonPathBooleanValue("$.meta.frescor.emDia").isTrue();
        assertThat(result).extractingJsonPathArrayValue("$.meta.cardsDegradados").containsExactly("atividade");
    }

    @Test
    @DisplayName("Deve desserializar JSON completo com meta para modular DashboardStats")
    void deveDesserializarModularDashboardStatsComMeta() throws IOException {
        String json = """
                {
                    "totalRevenue": 2500.00,
                    "revenueGrowth": 10.00,
                    "salesCount": 15,
                    "lowStockCount": 2,
                    "activityCount": 80,
                    "recentSales": [],
                    "chartData": [],
                    "meta": {
                        "geradoEm": "2026-10-15T10:00:00Z",
                        "frescor": {
                            "ultimoLancamentoLedger": 50,
                            "ultimoLancamentoProjetado": 50,
                            "pendentesRetry": 0,
                            "atraso": 0,
                            "emDia": true
                        },
                        "cardsDegradados": []
                    }
                }
                """;

        inv.dashboard.infrastructure.web.dto.DashboardStats parsed = modularJson.parseObject(json);

        assertThat(parsed).isNotNull();
        assertThat(parsed.totalRevenue()).isEqualByComparingTo("2500.00");
        assertThat(parsed.meta()).isNotNull();
        assertThat(parsed.meta().geradoEm()).isEqualTo(java.time.Instant.parse("2026-10-15T10:00:00Z"));
        assertThat(parsed.meta().frescor().emDia()).isTrue();
        assertThat(parsed.meta().cardsDegradados()).isEmpty();
    }

    @Test
    @DisplayName("Deve ignorar propriedades desconhecidas ao desserializar modular DashboardStats")
    void deveIgnorarPropriedadesDesconhecidasAoDesserializarModularDashboardStats() throws IOException {
        String jsonComCamposExtras = """
                {
                    "totalRevenue": 100.00,
                    "revenueGrowth": 0,
                    "salesCount": 1,
                    "lowStockCount": 0,
                    "activityCount": 0,
                    "recentSales": [],
                    "chartData": [],
                    "futureField": "unrecognized_value",
                    "anotherExtraNumber": 999
                }
                """;

        inv.dashboard.infrastructure.web.dto.DashboardStats parsed = modularJson.parseObject(jsonComCamposExtras);

        assertThat(parsed).isNotNull();
        assertThat(parsed.totalRevenue()).isEqualByComparingTo("100.00");
    }
}
