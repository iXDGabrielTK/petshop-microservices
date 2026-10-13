package inv.dashboard.infrastructure.provider;

import inv.dashboard.application.DashboardQueryService;
import inv.dashboard.application.DashboardSnapshot;
import inv.dashboard.infrastructure.legacy.LegacyDashboardStatsAdapter;
import inv.dashboard.infrastructure.provider.DefaultDashboardStatsProvider.EngineMode;
import inv.dashboard.infrastructure.shadow.DashboardParityComparator;
import inv.dashboard.infrastructure.shadow.ParityComparisonResult;
import inv.dashboard.infrastructure.web.DashboardStatsMapper;
import inv.dashboard.infrastructure.web.dto.DashboardStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultDashboardStatsProviderTest {

    @Mock
    private LegacyDashboardStatsAdapter legacyAdapter;

    @Mock
    private DashboardQueryService queryService;

    @Mock
    private DashboardStatsMapper mapper;

    @Mock
    private DashboardParityComparator comparator;

    private static final DashboardStats DTO_LEGACY = new DashboardStats(
            new BigDecimal("100.00"), BigDecimal.ZERO, 1L, 0L, 5L, List.of(), List.of()
    );

    private static final DashboardStats DTO_MODULAR = new DashboardStats(
            new BigDecimal("100.00"), BigDecimal.ZERO, 1L, 0L, 5L, List.of(), List.of(),
            new DashboardStats.MetaDTO(Instant.now(), null, List.of())
    );

    private static final DashboardSnapshot SNAPSHOT = new DashboardSnapshot(
            BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0L, 0L, List.of(), List.of(),
            new DashboardSnapshot.Meta(Instant.now(), null, List.of())
    );

    @Test
    @DisplayName("EngineMode.from deve parsear corretamente maiúsculas, minúsculas e valores com espaços")
    void deveParsearEngineModeCorretamente() {
        assertThat(EngineMode.from("legacy")).isEqualTo(EngineMode.LEGACY);
        assertThat(EngineMode.from("LEGACY")).isEqualTo(EngineMode.LEGACY);
        assertThat(EngineMode.from(" modular ")).isEqualTo(EngineMode.MODULAR);
        assertThat(EngineMode.from("SHADOW")).isEqualTo(EngineMode.SHADOW);
        assertThat(EngineMode.from(null)).isEqualTo(EngineMode.LEGACY);
        assertThat(EngineMode.from("")).isEqualTo(EngineMode.LEGACY);
        assertThat(EngineMode.from("unknown_mode")).isEqualTo(EngineMode.LEGACY);
    }

    @Test
    @DisplayName("Modo LEGACY: getStats deve delegar estritamente ao legacyAdapter")
    void deveDelegarParaLegacyAdapterEmModoLegacy() {
        when(legacyAdapter.getStats()).thenReturn(DTO_LEGACY);

        DefaultDashboardStatsProvider provider = new DefaultDashboardStatsProvider(
                "legacy", 0.10, legacyAdapter, queryService, mapper, comparator
        );

        DashboardStats stats = provider.getStats();

        assertThat(stats).isSameAs(DTO_LEGACY);
        assertThat(provider.getEngineMode()).isEqualTo(EngineMode.LEGACY);
        verify(legacyAdapter).getStats();
        verifyNoInteractions(queryService, comparator);
    }

    @Test
    @DisplayName("Modo MODULAR: getStats deve orquestrar consulta e mapeamento modular")
    void deveDelegarParaModularQueryServiceEmModoModular() {
        when(queryService.consultar()).thenReturn(SNAPSHOT);
        when(mapper.toDto(SNAPSHOT)).thenReturn(DTO_MODULAR);

        DefaultDashboardStatsProvider provider = new DefaultDashboardStatsProvider(
                "modular", 0.10, legacyAdapter, queryService, mapper, comparator
        );

        DashboardStats stats = provider.getStats();

        assertThat(stats).isSameAs(DTO_MODULAR);
        assertThat(provider.getEngineMode()).isEqualTo(EngineMode.MODULAR);
        verify(queryService).consultar();
        verify(mapper).toDto(SNAPSHOT);
        verifyNoInteractions(legacyAdapter, comparator);
    }

    @Test
    @DisplayName("Modo SHADOW: getStats deve retornar a resposta legada e permitir auditoria de paridade")
    void deveRetornarLegadoEmModoShadowEExecutarAuditoria() throws InterruptedException {
        when(legacyAdapter.getStats()).thenReturn(DTO_LEGACY);
        when(queryService.consultar()).thenReturn(SNAPSHOT);
        when(mapper.toDto(SNAPSHOT)).thenReturn(DTO_MODULAR);

        DefaultDashboardStatsProvider provider = new DefaultDashboardStatsProvider(
                "shadow", 1.0, legacyAdapter, queryService, mapper, comparator
        );

        DashboardStats stats = provider.getStats();

        assertThat(stats).isSameAs(DTO_LEGACY);
        assertThat(provider.getEngineMode()).isEqualTo(EngineMode.SHADOW);

        // Aguarda execução da thread virtual em background
        boolean concluido = provider.awaitIdleShadow(java.time.Duration.ofSeconds(2));
        assertThat(concluido).isTrue();

        verify(legacyAdapter).getStats();
        verify(queryService).consultar();
        verify(comparator).compareAndEmitParity(eq(DTO_LEGACY), eq(DTO_MODULAR));
        assertThat(provider.getAvailableShadowPermits()).isEqualTo(2);
    }

    @Test
    @DisplayName("Modo SHADOW síncrono: executeShadowSync deve comparar legado e modular retornando o resultado")
    void deveExecutarShadowSyncCorretamente() {
        when(legacyAdapter.getStats()).thenReturn(DTO_LEGACY);
        when(queryService.consultar()).thenReturn(SNAPSHOT);
        when(mapper.toDto(SNAPSHOT)).thenReturn(DTO_MODULAR);
        ParityComparisonResult expectedResult = new ParityComparisonResult(
                BigDecimal.ZERO, BigDecimal.ZERO, 0L, 0L, 0L, 0L, BigDecimal.ZERO, 0L, true
        );
        when(comparator.compareAndEmitParity(DTO_LEGACY, DTO_MODULAR)).thenReturn(expectedResult);

        DefaultDashboardStatsProvider provider = new DefaultDashboardStatsProvider(
                "shadow", 1.0, legacyAdapter, queryService, mapper, comparator
        );

        ParityComparisonResult result = provider.executeShadowSync();

        assertThat(result).isSameAs(expectedResult);
        verify(comparator).compareAndEmitParity(DTO_LEGACY, DTO_MODULAR);
    }

    @Test
    @DisplayName("Shutdown gracioso: provider encerra executor sem lançar exceções")
    void deveEncerrarExecutorGraciosamente() {
        DefaultDashboardStatsProvider provider = new DefaultDashboardStatsProvider(
                "shadow", 1.0, legacyAdapter, queryService, mapper, comparator
        );

        provider.shutdown();
        // Não deve lançar erro
    }
}
