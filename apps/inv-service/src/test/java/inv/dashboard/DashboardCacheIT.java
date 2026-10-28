package inv.dashboard;

import io.micrometer.core.instrument.MeterRegistry;
import inv.InvServiceApplication;
import inv.checkout.api.VendaQueryService;
import inv.dashboard.application.DashboardQueryService;
import inv.dashboard.application.DashboardSnapshot;
import inv.dashboard.application.port.AtividadeSistemaPort;
import inv.dashboard.infrastructure.cache.DashboardCacheConfig;
import inv.finance.api.FrescorProjecao;
import inv.finance.api.PeriodoContabil;
import inv.finance.api.ReceitaQueryService;
import inv.finance.api.ResumoReceita;
import inv.inventory.api.EstoqueQueryService;
import inv.inventory.api.ResumoEstoque;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Testes de integração do Cache Caffeine para o módulo inv.dashboard (DEBT-ARCH-001 / Etapa E5 / Q5).
 * Valida cache hit/miss, métricas no Micrometer, expiração e a regra 'unless' (snapshots com cards
 * degradados NÃO devem ser cacheados).
 */
@Testcontainers
@SpringBootTest(classes = InvServiceApplication.class)
class DashboardCacheIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

        registry.add("dashboard.cache.ttl", () -> "15s");
        registry.add("dashboard.cache.max-size", () -> "50");
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private ConnectionFactory connectionFactory;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private ReceitaQueryService receitas;

    @MockitoBean
    private VendaQueryService vendas;

    @MockitoBean
    private EstoqueQueryService estoque;

    @MockitoBean
    private AtividadeSistemaPort atividade;

    @Autowired
    private DashboardQueryService dashboardQueryService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        Cache cache = cacheManager.getCache(DashboardCacheConfig.CACHE_NAME);
        if (cache != null) {
            cache.clear();
        }
        reset(receitas, vendas, estoque, atividade);

        // Configuração padrão de mocks saudáveis
        when(receitas.resumir(any(PeriodoContabil.class))).thenReturn(new ResumoReceita(new BigDecimal("500.00"), BigDecimal.ZERO));
        when(receitas.serieDiaria(any(PeriodoContabil.class))).thenReturn(List.of());
        when(receitas.frescor()).thenReturn(new FrescorProjecao(10L, 10L, 0L));
        when(vendas.contarVendasConcluidas(any(), any())).thenReturn(10L);
        when(vendas.listarRecentes(anyInt())).thenReturn(List.of());
        when(estoque.resumo()).thenReturn(new ResumoEstoque(2L));
        when(atividade.contarEventosNaJanela(any(Duration.class))).thenReturn(OptionalLong.of(42L));
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (E5 / Q5): Snapshot íntegro é colocado em cache e chamadas subsequentes geram cache hit")
    void deveCachearSnapshotIntegroERegistrarHit() {
        Cache cache = cacheManager.getCache(DashboardCacheConfig.CACHE_NAME);
        assertThat(cache).isNotNull();
        assertThat(cache.get("snapshot")).isNull();

        // 1ª chamada: cache miss, invoca colaboradores
        DashboardSnapshot snapshot1 = dashboardQueryService.consultar();
        assertThat(snapshot1).isNotNull();
        assertThat(snapshot1.meta().cardsDegradados()).isEmpty();

        // Deve agora estar no cache
        assertThat(cache.get("snapshot")).isNotNull();
        DashboardSnapshot cachedValue = cache.get("snapshot", DashboardSnapshot.class);
        assertThat(cachedValue).isNotNull();
        assertThat(cachedValue.receitaHoje()).isEqualByComparingTo(snapshot1.receitaHoje());

        // 2ª chamada: deve vir do cache
        DashboardSnapshot snapshot2 = dashboardQueryService.consultar();
        assertThat(snapshot2).isNotNull();
        assertThat(snapshot2.meta().geradoEm()).isEqualTo(snapshot1.meta().geradoEm());

        // Colaboradores chamados somente 1 vez (chamada 1), pois a chamada 2 foi servida do cache!
        verify(vendas, times(1)).contarVendasConcluidas(any(), any());
        verify(estoque, times(1)).resumo();
        verify(atividade, times(1)).contarEventosNaJanela(any());

        // Métrica de cache hit registrada
        double hits = meterRegistry.find("cache.gets")
                .tag("cache", DashboardCacheConfig.CACHE_NAME)
                .tag("result", "hit")
                .functionCounter()
                .count();
        assertThat(hits).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (E5 / Q5): Snapshot com card degradado NÃO é colocado em cache (regra unless)")
    void deveNaoCachearSnapshotDegradadoPermitindoRecuperacaoImediata() {
        Cache cache = cacheManager.getCache(DashboardCacheConfig.CACHE_NAME);
        assertThat(cache).isNotNull();

        // Simula falha na telemetria de atividade (degrada o card "atividade")
        when(atividade.contarEventosNaJanela(any())).thenReturn(OptionalLong.empty());

        // 1ª chamada: retorna snapshot degradado
        DashboardSnapshot snapshotDegradado = dashboardQueryService.consultar();
        assertThat(snapshotDegradado.meta().cardsDegradados()).contains("atividade");

        // Regra unless: como cardsDegradados não está vazio, NÃO deve ser colocado no cache!
        assertThat(cache.get("snapshot")).isNull();

        // Restabelece a telemetria para a chamada seguinte
        when(atividade.contarEventosNaJanela(any())).thenReturn(OptionalLong.of(99L));

        // 2ª chamada: como não foi cacheada a versão degradada, executa imediatamente sem esperar TTL!
        DashboardSnapshot snapshotRecuperado = dashboardQueryService.consultar();
        assertThat(snapshotRecuperado.meta().cardsDegradados()).isEmpty();
        assertThat(snapshotRecuperado.eventosAtividade()).isEqualTo(99L);

        // Agora que está íntegro, foi colocado no cache
        assertThat(cache.get("snapshot")).isNotNull();
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (E5 / Q5): Limpeza manual do cache força recarga na próxima consulta")
    void deveRecarregarAposEviccaoDoCache() {
        Cache cache = cacheManager.getCache(DashboardCacheConfig.CACHE_NAME);
        assertThat(cache).isNotNull();

        dashboardQueryService.consultar();
        assertThat(cache.get("snapshot")).isNotNull();
        verify(vendas, times(1)).contarVendasConcluidas(any(), any());

        // Limpa cache
        cache.clear();
        assertThat(cache.get("snapshot")).isNull();

        // Próxima chamada invoca novamente os colaboradores
        dashboardQueryService.consultar();
        verify(vendas, times(2)).contarVendasConcluidas(any(), any());
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (E5 / Q5): Proteção contra Cache Stampede em requisições altamente concorrentes")
    void deveProtegerContraCacheStampedeEmRequisicoesConcorrentes() throws Exception {
        Cache cache = cacheManager.getCache(DashboardCacheConfig.CACHE_NAME);
        assertThat(cache).isNotNull();
        cache.clear();

        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        List<Future<DashboardSnapshot>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                try {
                    return dashboardQueryService.consultar();
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        // Libera todas as threads simultaneamente no exato mesmo milissegundo contra o cache vazio
        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        for (Future<DashboardSnapshot> f : futures) {
            DashboardSnapshot s = f.get();
            assertThat(s).isNotNull();
            assertThat(s.meta().cardsDegradados()).isEmpty();
        }

        // Apesar de 20 chamadas simultâneas concorrentes com cache miss,
        // os colaboradores OLTP foram invocados exatamente UMA vez (proteção contra stampede)!
        verify(vendas, times(1)).contarVendasConcluidas(any(), any());
        verify(estoque, times(1)).resumo();
    }
}
