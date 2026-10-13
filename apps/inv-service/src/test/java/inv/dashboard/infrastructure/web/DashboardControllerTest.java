package inv.dashboard.infrastructure.web;

import inv.dashboard.application.DashboardStatsProvider;
import inv.dashboard.infrastructure.web.dto.DashboardStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DashboardControllerTest {

    private MockMvc mockMvc;

    @Mock
    private DashboardStatsProvider statsProvider;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json()
                .modules(new JavaTimeModule())
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        mockMvc = MockMvcBuilders.standaloneSetup(new DashboardController(statsProvider))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("GET /dashboard/stats deve responder 200 com payload fornecido pelo DashboardStatsProvider")
    void deveRetornarStatsComSucesso() throws Exception {
        DashboardStats stats = new DashboardStats(
                new BigDecimal("1500.00"),
                new BigDecimal("12.50"),
                10L,
                2L,
                45L,
                List.of(new DashboardStats.RecentSaleDTO("1", "Ração Golden", new BigDecimal("150.00"), "14:30")),
                List.of(new DashboardStats.ChartDataDTO("seg.", new BigDecimal("1500.00"))),
                new DashboardStats.MetaDTO(Instant.parse("2026-10-15T12:00:00Z"), null, List.of())
        );

        when(statsProvider.getStats()).thenReturn(stats);

        mockMvc.perform(get("/dashboard/stats")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRevenue").value(1500.00))
                .andExpect(jsonPath("$.revenueGrowth").value(12.50))
                .andExpect(jsonPath("$.salesCount").value(10))
                .andExpect(jsonPath("$.lowStockCount").value(2))
                .andExpect(jsonPath("$.activityCount").value(45))
                .andExpect(jsonPath("$.recentSales[0].productName").value("Ração Golden"))
                .andExpect(jsonPath("$.chartData[0].name").value("seg."))
                .andExpect(jsonPath("$.meta.geradoEm").value("2026-10-15T12:00:00Z"));

        verify(statsProvider).getStats();
    }
}
