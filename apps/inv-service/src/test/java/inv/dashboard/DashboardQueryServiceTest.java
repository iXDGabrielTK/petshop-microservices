package inv.dashboard;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import inv.checkout.api.VendaQueryService;
import inv.checkout.api.VendaRecenteView;
import inv.dashboard.application.DashboardQueryService;
import inv.dashboard.application.DashboardSnapshot;
import inv.dashboard.application.port.AtividadeSistemaPort;
import inv.finance.api.FrescorProjecao;
import inv.finance.api.PeriodoContabil;
import inv.finance.api.ReceitaDiariaView;
import inv.finance.api.ReceitaQueryService;
import inv.finance.api.ResumoReceita;
import inv.inventory.api.EstoqueQueryService;
import inv.inventory.api.ResumoEstoque;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardQueryServiceTest {

    @Mock
    private ReceitaQueryService receitas;

    @Mock
    private VendaQueryService vendas;

    @Mock
    private EstoqueQueryService estoque;

    @Mock
    private AtividadeSistemaPort atividade;

    private Clock fixedClock;
    private SimpleMeterRegistry meterRegistry;
    private DashboardQueryService service;

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final Instant NOW = Instant.parse("2026-10-15T14:30:00Z");

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(NOW, ZONE);
        meterRegistry = new SimpleMeterRegistry();
        service = new DashboardQueryService(receitas, vendas, estoque, atividade, fixedClock, meterRegistry);
    }

    @Test
    @DisplayName("Deve consultar dashboard com sucesso consolidando métricas no cenário ideal")
    void deveConsultarDashboardComSucessoNoCenarioIdeal() {
        LocalDate hoje = LocalDate.now(fixedClock);

        when(receitas.resumir(PeriodoContabil.dia(hoje)))
                .thenReturn(new ResumoReceita(new BigDecimal("500.00"), new BigDecimal("100.00")));

        // MTD mesAtual (1 a 15 de Outubro): creditos 4000, debitos 1000 -> liquida 3000
        when(receitas.resumir(new PeriodoContabil(LocalDate.of(2026, 10, 1), hoje)))
                .thenReturn(new ResumoReceita(new BigDecimal("4000.00"), new BigDecimal("1000.00")));

        // MTD mesAnterior (1 a 15 de Setembro): creditos 2500, debitos 500 -> liquida 2000
        when(receitas.resumir(new PeriodoContabil(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15))))
                .thenReturn(new ResumoReceita(new BigDecimal("2500.00"), new BigDecimal("500.00")));

        List<ReceitaDiariaView> serie = List.of(
                new ReceitaDiariaView(hoje.minusDays(1), new BigDecimal("100.00"), BigDecimal.ZERO),
                new ReceitaDiariaView(hoje, new BigDecimal("400.00"), BigDecimal.ZERO)
        );
        when(receitas.serieDiaria(any(PeriodoContabil.class))).thenReturn(serie);

        when(vendas.contarVendasConcluidas(hoje.atStartOfDay(), hoje.plusDays(1).atStartOfDay()))
                .thenReturn(8L);

        List<VendaRecenteView> recentes = List.of(
                new VendaRecenteView(101L, "Ração Golden", 2, new BigDecimal("150.00"), LocalDateTime.now(fixedClock))
        );
        when(vendas.listarRecentes(5)).thenReturn(recentes);

        when(estoque.resumo()).thenReturn(new ResumoEstoque(3L));
        when(atividade.contarEventosNaJanela(Duration.ofHours(1))).thenReturn(OptionalLong.of(42L));

        FrescorProjecao frescor = new FrescorProjecao(150L, 150L, 0L);
        when(receitas.frescor()).thenReturn(frescor);

        DashboardSnapshot snapshot = service.consultar();

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.receitaHoje()).isEqualByComparingTo(new BigDecimal("400.00"));
        // Growth: (3000 - 2000) / 2000 * 100 = 50.0000%
        assertThat(snapshot.crescimentoMes()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(snapshot.vendasHoje()).isEqualTo(8L);
        assertThat(snapshot.produtosEstoqueBaixo()).isEqualTo(3L);
        assertThat(snapshot.eventosAtividade()).isEqualTo(42L);
        assertThat(snapshot.vendasRecentes()).hasSize(1);
        assertThat(snapshot.serieDiaria()).hasSize(2);
        assertThat(snapshot.meta().cardsDegradados()).isEmpty();
        assertThat(snapshot.meta().frescor()).isEqualTo(frescor);
        assertThat(snapshot.meta().frescor().emDia()).isTrue();

        assertThat(meterRegistry.find("dashboard.card.latency").tag("card", "receitaHoje").timer()).isNotNull();
    }

    @Test
    @DisplayName("Decisão Q2: Deve calcular crescimento neutro (zero) quando o mês anterior de referência for zerado")
    void deveCalcularCrescimentoNeutroQuandoMesAnteriorForZero() {
        LocalDate hoje = LocalDate.now(fixedClock);

        when(receitas.resumir(PeriodoContabil.dia(hoje))).thenReturn(ResumoReceita.ZERO);
        when(receitas.resumir(new PeriodoContabil(LocalDate.of(2026, 10, 1), hoje)))
                .thenReturn(new ResumoReceita(new BigDecimal("1500.00"), BigDecimal.ZERO));
        when(receitas.resumir(new PeriodoContabil(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15))))
                .thenReturn(ResumoReceita.ZERO);

        when(receitas.serieDiaria(any(PeriodoContabil.class))).thenReturn(List.of());
        when(vendas.contarVendasConcluidas(any(), any())).thenReturn(0L);
        when(vendas.listarRecentes(5)).thenReturn(List.of());
        when(estoque.resumo()).thenReturn(new ResumoEstoque(0L));
        when(atividade.contarEventosNaJanela(any())).thenReturn(OptionalLong.of(0L));
        when(receitas.frescor()).thenReturn(new FrescorProjecao(0L, 0L, 0L));

        DashboardSnapshot snapshot = service.consultar();

        assertThat(snapshot.crescimentoMes()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(snapshot.meta().cardsDegradados()).isEmpty();
    }

    @Test
    @DisplayName("Degradação graciosa: deve marcar 'atividade' como card degradado quando Redis retornar vazio")
    void deveDegradarCardAtividadeQuandoRedisEstiverVazioOuIndisponivel() {
        LocalDate hoje = LocalDate.now(fixedClock);

        when(receitas.resumir(any())).thenReturn(ResumoReceita.ZERO);
        when(receitas.serieDiaria(any())).thenReturn(List.of());
        when(vendas.contarVendasConcluidas(any(), any())).thenReturn(5L);
        when(vendas.listarRecentes(5)).thenReturn(List.of());
        when(estoque.resumo()).thenReturn(new ResumoEstoque(1L));
        when(atividade.contarEventosNaJanela(any())).thenReturn(OptionalLong.empty());
        when(receitas.frescor()).thenReturn(new FrescorProjecao(10L, 10L, 0L));

        DashboardSnapshot snapshot = service.consultar();

        assertThat(snapshot.eventosAtividade()).isEqualTo(0L);
        assertThat(snapshot.meta().cardsDegradados()).containsExactly("atividade");
        assertThat(meterRegistry.find("dashboard.card.failure").tag("card", "atividade").counter().count()).isEqualTo(1.0);
        // Demais cards continuam operando normalmente
        assertThat(snapshot.vendasHoje()).isEqualTo(5L);
        assertThat(snapshot.produtosEstoqueBaixo()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Degradação graciosa: deve isolar falha no serviço de receitas registrando card degradado")
    void deveDegradarCardsReceitaQuandoServicoDeReceitaFalhar() {
        LocalDate hoje = LocalDate.now(fixedClock);

        when(receitas.resumir(PeriodoContabil.dia(hoje)))
                .thenThrow(new RuntimeException("PostgreSQL connection timeout"));
        when(receitas.resumir(new PeriodoContabil(LocalDate.of(2026, 10, 1), hoje)))
                .thenReturn(new ResumoReceita(new BigDecimal("100.00"), BigDecimal.ZERO));
        when(receitas.resumir(new PeriodoContabil(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15))))
                .thenReturn(new ResumoReceita(new BigDecimal("50.00"), BigDecimal.ZERO));

        when(receitas.serieDiaria(any())).thenReturn(List.of());
        when(vendas.contarVendasConcluidas(any(), any())).thenReturn(3L);
        when(vendas.listarRecentes(5)).thenReturn(List.of());
        when(estoque.resumo()).thenReturn(new ResumoEstoque(0L));
        when(atividade.contarEventosNaJanela(any())).thenReturn(OptionalLong.of(10L));
        when(receitas.frescor()).thenReturn(new FrescorProjecao(50L, 50L, 0L));

        DashboardSnapshot snapshot = service.consultar();

        assertThat(snapshot.receitaHoje()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(snapshot.meta().cardsDegradados()).contains("receitaHoje");
        assertThat(meterRegistry.find("dashboard.card.failure").tag("card", "receitaHoje").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Degradação graciosa: deve isolar falha na consulta de estoque")
    void deveDegradarCardEstoqueQuandoServicoDeEstoqueFalhar() {
        when(receitas.resumir(any())).thenReturn(ResumoReceita.ZERO);
        when(receitas.serieDiaria(any())).thenReturn(List.of());
        when(vendas.contarVendasConcluidas(any(), any())).thenReturn(4L);
        when(vendas.listarRecentes(5)).thenReturn(List.of());
        when(estoque.resumo()).thenThrow(new RuntimeException("Inventory service error"));
        when(atividade.contarEventosNaJanela(any())).thenReturn(OptionalLong.of(5L));
        when(receitas.frescor()).thenReturn(new FrescorProjecao(0L, 0L, 0L));

        DashboardSnapshot snapshot = service.consultar();

        assertThat(snapshot.produtosEstoqueBaixo()).isEqualTo(0L);
        assertThat(snapshot.meta().cardsDegradados()).contains("estoqueBaixo");
        assertThat(meterRegistry.find("dashboard.card.failure").tag("card", "estoqueBaixo").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Degradação graciosa: deve isolar falha na consulta de frescor")
    void deveDegradarCardFrescorQuandoConsultaFalhar() {
        when(receitas.resumir(any())).thenReturn(ResumoReceita.ZERO);
        when(receitas.serieDiaria(any())).thenReturn(List.of());
        when(vendas.contarVendasConcluidas(any(), any())).thenReturn(0L);
        when(vendas.listarRecentes(5)).thenReturn(List.of());
        when(estoque.resumo()).thenReturn(new ResumoEstoque(0L));
        when(atividade.contarEventosNaJanela(any())).thenReturn(OptionalLong.of(1L));
        when(receitas.frescor()).thenThrow(new RuntimeException("Lag query failed"));

        DashboardSnapshot snapshot = service.consultar();

        assertThat(snapshot.meta().frescor()).isNull();
        assertThat(snapshot.meta().cardsDegradados()).contains("frescor");
    }

    @Test
    @DisplayName("Decisão Q2: Deve calcular MTD vs MTD corretamente em fim de mês com comprimentos de mês diferentes (31 vs 28)")
    void deveCalcularMTDvsMTDEmFimDeMesComComprimentosDiferentes() {
        // Data fixada em 31 de Março: o mês anterior (Fevereiro) tem 28 dias
        Instant fimDeMarco = Instant.parse("2026-03-31T12:00:00Z");
        Clock clockFimMarco = Clock.fixed(fimDeMarco, ZONE);
        DashboardQueryService serviceMarco = new DashboardQueryService(receitas, vendas, estoque, atividade, clockFimMarco, meterRegistry);

        LocalDate hoje = LocalDate.of(2026, 3, 31);
        // Espera-se período de 1 a 31 de Março
        PeriodoContabil esperadoMarco = new PeriodoContabil(LocalDate.of(2026, 3, 1), hoje);
        // Espera-se período de 1 a 28 de Fevereiro (min(31, 28))
        PeriodoContabil esperadoFevereiro = new PeriodoContabil(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

        when(receitas.resumir(PeriodoContabil.dia(hoje))).thenReturn(ResumoReceita.ZERO);
        when(receitas.resumir(esperadoMarco)).thenReturn(new ResumoReceita(new BigDecimal("3100.00"), BigDecimal.ZERO));
        when(receitas.resumir(esperadoFevereiro)).thenReturn(new ResumoReceita(new BigDecimal("2800.00"), BigDecimal.ZERO));

        when(receitas.serieDiaria(any())).thenReturn(List.of());
        when(vendas.contarVendasConcluidas(any(), any())).thenReturn(0L);
        when(vendas.listarRecentes(5)).thenReturn(List.of());
        when(estoque.resumo()).thenReturn(new ResumoEstoque(0L));
        when(atividade.contarEventosNaJanela(any())).thenReturn(OptionalLong.of(0L));
        when(receitas.frescor()).thenReturn(new FrescorProjecao(0L, 0L, 0L));

        DashboardSnapshot snapshot = serviceMarco.consultar();

        assertThat(snapshot).isNotNull();
        // (3100 - 2800) / 2800 * 100 = 0.1071 * 100 = 10.7100
        assertThat(snapshot.crescimentoMes()).isEqualByComparingTo(new BigDecimal("10.7100"));
    }

    @Test
    @DisplayName("Degradação graciosa: deve degradar crescimento para zero quando a consulta do mês falhar")
    void deveDegradarCrescimentoParaZeroQuandoConsultaDoMesFalhar() {
        LocalDate hoje = LocalDate.now(fixedClock);

        when(receitas.resumir(PeriodoContabil.dia(hoje))).thenReturn(ResumoReceita.ZERO);
        when(receitas.resumir(new PeriodoContabil(LocalDate.of(2026, 10, 1), hoje)))
                .thenThrow(new RuntimeException("Falha na consulta de receita do mês atual"));
        when(receitas.resumir(new PeriodoContabil(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15))))
                .thenReturn(new ResumoReceita(new BigDecimal("2000.00"), BigDecimal.ZERO));

        when(receitas.serieDiaria(any())).thenReturn(List.of());
        when(vendas.contarVendasConcluidas(any(), any())).thenReturn(0L);
        when(vendas.listarRecentes(5)).thenReturn(List.of());
        when(estoque.resumo()).thenReturn(new ResumoEstoque(0L));
        when(atividade.contarEventosNaJanela(any())).thenReturn(OptionalLong.of(0L));
        when(receitas.frescor()).thenReturn(new FrescorProjecao(0L, 0L, 0L));

        DashboardSnapshot snapshot = service.consultar();

        assertThat(snapshot.crescimentoMes()).isEqualTo(BigDecimal.ZERO);
        assertThat(snapshot.meta().cardsDegradados()).contains("receitaMes");
        assertThat(meterRegistry.find("dashboard.card.failure").tag("card", "receitaMes").counter().count()).isEqualTo(1.0);
    }
}
