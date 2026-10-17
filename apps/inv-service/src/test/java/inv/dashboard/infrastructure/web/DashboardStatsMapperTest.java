package inv.dashboard.infrastructure.web;

import inv.checkout.api.VendaRecenteView;
import inv.dashboard.application.DashboardSnapshot;
import inv.dashboard.infrastructure.web.dto.DashboardStats;
import inv.finance.api.FrescorProjecao;
import inv.finance.api.ReceitaDiariaView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardStatsMapperTest {

    private DashboardStatsMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new DashboardStatsMapper();
    }

    @Test
    @DisplayName("Deve mapear venda com múltiplos itens adicionando sufixo '+ outros'")
    void deveMapearVendaComMultiplosItens() {
        VendaRecenteView view = new VendaRecenteView(
                1L, "Ração Golden 15kg", 3, new BigDecimal("250.00"), LocalDateTime.of(2026, 10, 15, 14, 30)
        );
        DashboardSnapshot snapshot = new DashboardSnapshot(
                BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0L, 0L, List.of(view), List.of(),
                new DashboardSnapshot.Meta(Instant.now(), null, List.of())
        );

        DashboardStats dto = mapper.toDto(snapshot);

        assertThat(dto.recentSales()).hasSize(1);
        DashboardStats.RecentSaleDTO sale = dto.recentSales().getFirst();
        assertThat(sale.id()).isEqualTo("1");
        assertThat(sale.productName()).isEqualTo("Ração Golden 15kg + outros");
        assertThat(sale.amount()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(sale.time()).isEqualTo("14:30");
    }

    @Test
    @DisplayName("Deve mapear venda com apenas 1 item sem sufixo")
    void deveMapearVendaComUmItem() {
        VendaRecenteView view = new VendaRecenteView(
                2L, "Brinquedo Mordedor", 1, new BigDecimal("35.00"), LocalDateTime.of(2026, 10, 15, 9, 15)
        );
        DashboardSnapshot snapshot = new DashboardSnapshot(
                BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0L, 0L, List.of(view), List.of(),
                new DashboardSnapshot.Meta(Instant.now(), null, List.of())
        );

        DashboardStats dto = mapper.toDto(snapshot);

        assertThat(dto.recentSales()).hasSize(1);
        DashboardStats.RecentSaleDTO sale = dto.recentSales().getFirst();
        assertThat(sale.productName()).isEqualTo("Brinquedo Mordedor");
        assertThat(sale.time()).isEqualTo("09:15");
    }

    @Test
    @DisplayName("Deve mapear venda sem itens ou com produto nulo como 'Venda sem itens'")
    void deveMapearVendaSemItens() {
        VendaRecenteView view = new VendaRecenteView(
                3L, null, 0, BigDecimal.ZERO, LocalDateTime.of(2026, 10, 15, 18, 0)
        );
        DashboardSnapshot snapshot = new DashboardSnapshot(
                BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0L, 0L, List.of(view), List.of(),
                new DashboardSnapshot.Meta(Instant.now(), null, List.of())
        );

        DashboardStats dto = mapper.toDto(snapshot);

        assertThat(dto.recentSales()).hasSize(1);
        assertThat(dto.recentSales().getFirst().productName()).isEqualTo("Venda sem itens");
    }

    @Test
    @DisplayName("Deve mapear chartData formatando dia da semana em português (pt-BR)")
    void deveMapearChartDataComDiaDaSemanaEmPortugues() {
        // 2026-10-15 é uma quinta-feira ("qui." em pt-BR)
        LocalDate quinta = LocalDate.of(2026, 10, 15);
        ReceitaDiariaView r = new ReceitaDiariaView(quinta, new BigDecimal("1200.00"), new BigDecimal("200.00"));

        DashboardSnapshot snapshot = new DashboardSnapshot(
                BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0L, 0L, List.of(), List.of(r),
                new DashboardSnapshot.Meta(Instant.now(), null, List.of())
        );

        DashboardStats dto = mapper.toDto(snapshot);

        assertThat(dto.chartData()).hasSize(1);
        DashboardStats.ChartDataDTO chart = dto.chartData().getFirst();
        assertThat(chart.name()).isEqualTo("qui.");
        assertThat(chart.total()).isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    @Test
    @DisplayName("Deve mapear meta com frescor e cards degradados")
    void deveMapearMetaCompleto() {
        Instant agora = Instant.parse("2026-10-15T12:00:00Z");
        FrescorProjecao frescor = new FrescorProjecao(100L, 95L, 2L);
        DashboardSnapshot.Meta meta = new DashboardSnapshot.Meta(agora, frescor, List.of("atividade"));

        DashboardSnapshot snapshot = new DashboardSnapshot(
                new BigDecimal("500.00"), new BigDecimal("10.00"), 5L, 2L, 0L, List.of(), List.of(), meta
        );

        DashboardStats dto = mapper.toDto(snapshot);

        assertThat(dto.meta()).isNotNull();
        assertThat(dto.meta().geradoEm()).isEqualTo(agora);
        assertThat(dto.meta().cardsDegradados()).containsExactly("atividade");
        assertThat(dto.meta().frescor()).isNotNull();
        assertThat(dto.meta().frescor().ultimoLancamentoLedger()).isEqualTo(100L);
        assertThat(dto.meta().frescor().ultimoLancamentoProjetado()).isEqualTo(95L);
        assertThat(dto.meta().frescor().atraso()).isEqualTo(5L);
        assertThat(dto.meta().frescor().emDia()).isFalse();
    }
}
