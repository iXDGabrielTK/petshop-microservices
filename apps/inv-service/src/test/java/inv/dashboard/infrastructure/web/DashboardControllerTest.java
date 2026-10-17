package inv.dashboard.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import inv.checkout.api.VendaRecenteView;
import inv.dashboard.application.DashboardQueryService;
import inv.dashboard.application.DashboardSnapshot;
import inv.finance.api.FrescorProjecao;
import inv.finance.api.ReceitaDiariaView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private DashboardQueryService queryService;

    private final DashboardStatsMapper mapper = new DashboardStatsMapper();

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json()
                .modules(new JavaTimeModule())
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        mockMvc = MockMvcBuilders.standaloneSetup(new DashboardController(queryService, mapper))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("GET /dashboard/stats deve responder 200 com snapshot consultado do DashboardQueryService e mapeado para DTO")
    void deveRetornarStatsComSucesso() throws Exception {
        DashboardSnapshot snapshot = new DashboardSnapshot(
                new BigDecimal("1500.00"),
                new BigDecimal("12.50"),
                10L,
                2L,
                45L,
                List.of(new VendaRecenteView(1L, "Ração Golden", 1, new BigDecimal("150.00"), LocalDateTime.of(2026, 10, 15, 14, 30))),
                List.of(new ReceitaDiariaView(LocalDate.of(2026, 10, 12), new BigDecimal("1500.00"), BigDecimal.ZERO)),
                new DashboardSnapshot.Meta(
                        Instant.parse("2026-10-15T12:00:00Z"),
                        new FrescorProjecao(10L, 10L, 0L),
                        List.of()
                )
        );

        when(queryService.consultar()).thenReturn(snapshot);

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
                .andExpect(jsonPath("$.meta.geradoEm").value("2026-10-15T12:00:00Z"))
                .andExpect(jsonPath("$.meta.frescor.emDia").value(true));

        verify(queryService).consultar();
    }
}

