package inv.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import common.telemetry.TelemetriaKeys;
import io.micrometer.core.instrument.MeterRegistry;
import inv.checkout.domain.model.Pagamento;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.dashboard.application.DashboardQueryService;
import inv.dashboard.application.DashboardSnapshot;
import inv.dashboard.infrastructure.legacy.LegacyDashboardStatsAdapter;
import inv.dashboard.infrastructure.provider.DefaultDashboardStatsProvider;
import inv.dashboard.infrastructure.shadow.DashboardParityComparator;
import inv.dashboard.infrastructure.shadow.ParityComparisonResult;
import inv.dashboard.infrastructure.web.DashboardStatsMapper;
import inv.dashboard.infrastructure.web.dto.DashboardStats;
import inv.finance.infrastructure.jobs.ProjecaoDiariaCatchUpJob;
import inv.finance.infrastructure.persistence.ProjecaoReceitaDiariaRepository;
import inv.finance.usecase.DashboardService;
import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.UnidadeMedida;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import inv.shared.domain.MetodoPagamento;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de integração de Paridade entre as engines Legacy e Modular do Dashboard (DEBT-ARCH-001 / Etapa E4).
 * <p>
 * Executa contra PostgreSQL e Redis reais via Testcontainers.
 * Objetivos:
 * 1. Validar paridade exata (delta = 0) em métricas não financeiras (vendas, estoque, atividade) no mesmo seed da E0;
 * 2. Comprovar que vendas concluídas possuem lista de recentes e contagens idênticas;
 * 3. Documentar e auditar divergências esperadas em receita entre os regimes de competência e caixa (F6 e Q1);
 * 4. Documentar o filtro de status de domínio aprovado na decisão Q3 (vendas em aberto apareciam no legado e são filtradas no modular);
 * 5. Validar que a engine modular resolve o achado F5 (LazyInitializationException) sem precisar de transação HTTP;
 * 6. Validar a emissão de métricas Micrometer ('dashboard.parity.delta') pelo comparador de paridade.
 */
@Testcontainers
@SpringBootTest(classes = inv.InvServiceApplication.class)
@AutoConfigureMockMvc(addFilters = false)
class DashboardParityIT {

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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "30");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

        registry.add("finance.projection.diaria.enabled", () -> "true");
        registry.add("dashboard.engine", () -> "shadow");
        registry.add("dashboard.shadow.sample-rate", () -> "1.0");
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private ConnectionFactory connectionFactory;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DashboardService legacyService;

    @Autowired
    private LegacyDashboardStatsAdapter legacyAdapter;

    @Autowired
    private DashboardQueryService modularQueryService;

    @Autowired
    private DefaultDashboardStatsProvider statsProvider;

    @Autowired
    private DashboardStatsMapper mapper;

    @Autowired
    private DashboardParityComparator parityComparator;

    @Autowired
    private VendaRepository vendaRepository;

    @Autowired
    private ProdutoRepository produtoRepository;

    @Autowired
    private ProjecaoReceitaDiariaRepository projecaoDiariaRepository;

    @Autowired
    private ProjecaoDiariaCatchUpJob catchUpJob;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() throws Exception {
        statsProvider.awaitIdleShadow(java.time.Duration.ofSeconds(2));
        Cache cache = cacheManager.getCache(inv.dashboard.infrastructure.cache.DashboardCacheConfig.CACHE_NAME);
        if (cache != null) {
            cache.clear();
        }

        jdbcTemplate.update("DELETE FROM itens_venda");
        jdbcTemplate.update("DELETE FROM pagamentos");
        jdbcTemplate.update("DELETE FROM movimentacoes_estoque");
        jdbcTemplate.update("DELETE FROM outbox");
        jdbcTemplate.update("DELETE FROM financial_projection_checkpoint");
        jdbcTemplate.update("DELETE FROM financial_projection_checkpoint_v2");
        jdbcTemplate.update("DELETE FROM financial_projection_venda");
        jdbcTemplate.update("DELETE FROM financial_projection_diaria_checkpoint");
        jdbcTemplate.update("DELETE FROM financial_projection_diaria");
        jdbcTemplate.update("DELETE FROM projection_retry_queue");
        jdbcTemplate.update("DELETE FROM fechamento_caixa_diario");
        jdbcTemplate.update("DELETE FROM lancamentos_financeiros");
        vendaRepository.deleteAll();
        produtoRepository.deleteAll();

        redisTemplate.delete(TelemetriaKeys.ATIVIDADE_ZSET);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() throws Exception {
        statsProvider.awaitIdleShadow(java.time.Duration.ofSeconds(2));
        Cache cache = cacheManager.getCache(inv.dashboard.infrastructure.cache.DashboardCacheConfig.CACHE_NAME);
        if (cache != null) {
            cache.clear();
        }
    }

    @Test
    @DisplayName("Paridade no baseline: banco sem vendas deve apresentar delta ZERO em todas as métricas entre legado e modular")
    void deveApresentarParidadeExataEmBancoVazio() {
        // Setup igual ao E0: 2 produtos com estoque baixo, 1 normal
        Produto p1 = criarProduto("COD-001", "Ração Filhote 1kg", new BigDecimal("45.00"), new BigDecimal("2"), new BigDecimal("10"));
        Produto p2 = criarProduto("COD-002", "Antipulgas Pipeta", new BigDecimal("80.00"), BigDecimal.ZERO, new BigDecimal("5"));
        Produto p3 = criarProduto("COD-003", "Shampoo Neutro 500ml", new BigDecimal("25.00"), new BigDecimal("50"), new BigDecimal("10"));
        produtoRepository.saveAll(List.of(p1, p2, p3));

        // Telemetria Redis: 3 na janela de 1h, 1 expirado
        long agora = Instant.now().toEpochMilli();
        redisTemplate.opsForZSet().add(TelemetriaKeys.ATIVIDADE_ZSET, "user-1", agora - 60_000);
        redisTemplate.opsForZSet().add(TelemetriaKeys.ATIVIDADE_ZSET, "user-2", agora - 120_000);
        redisTemplate.opsForZSet().add(TelemetriaKeys.ATIVIDADE_ZSET, "user-3", agora - 180_000);
        redisTemplate.opsForZSet().add(TelemetriaKeys.ATIVIDADE_ZSET, "user-old", agora - 4_000_000);

        DashboardStats legacyStats = legacyAdapter.toDto(legacyService.getStats());
        DashboardStats modularStats = mapper.toDto(modularQueryService.consultar());

        ParityComparisonResult result = parityComparator.compareAndEmitParity(legacyStats, modularStats);

        assertThat(result.nonFinancialEqual()).isTrue();
        assertThat(result.hasAnyDelta()).isFalse();

        assertThat(modularStats.totalRevenue()).isEqualByComparingTo(legacyStats.totalRevenue());
        assertThat(modularStats.salesCount()).isEqualTo(legacyStats.salesCount());
        assertThat(modularStats.lowStockCount()).isEqualTo(legacyStats.lowStockCount()).isEqualTo(2L);
        assertThat(modularStats.activityCount()).isEqualTo(legacyStats.activityCount()).isEqualTo(3L);
        assertThat(modularStats.recentSales()).isEmpty();
        assertThat(modularStats.chartData()).hasSize(7);
    }

    @Test
    @DisplayName("Paridade com vendas concluídas e projeção sincronizada: métricas não financeiras e vendas recentes são 100% idênticas")
    void deveApresentarParidadeExataEmVendasConcluidasComProjecaoSincronizada() {
        Produto p1 = produtoRepository.save(criarProduto("P1", "Ração Seca 10kg", new BigDecimal("120.00"), new BigDecimal("15"), new BigDecimal("5")));
        Produto p2 = produtoRepository.save(criarProduto("P2", "Biscoito Canino 500g", new BigDecimal("30.00"), new BigDecimal("2"), new BigDecimal("5")));

        LocalDate hoje = LocalDate.now();
        LocalDateTime agoraHora = hoje.atTime(14, 0);

        // Venda 1: 2 itens (deve exibir '+ outros'), concluída hoje
        Venda v1 = new Venda();
        v1.adicionarItem(p1, BigDecimal.ONE);
        v1.adicionarItem(p2, BigDecimal.ONE);
        v1.irParaPagamento();
        Pagamento pag1 = new Pagamento(v1, MetodoPagamento.CARTAO_CREDITO, new BigDecimal("150.00"), UUID.randomUUID());
        pag1.processar();
        pag1.confirmar(new BigDecimal("150.00"), "NSU-001");
        v1.registrarPagamentoConfirmado(pag1);
        v1.concluir();
        vendaRepository.save(v1);

        // Venda 2: 1 item, concluída hoje
        Venda v2 = new Venda();
        v2.adicionarItem(p2, BigDecimal.ONE);
        v2.irParaPagamento();
        Pagamento pag2 = new Pagamento(v2, MetodoPagamento.PIX, new BigDecimal("30.00"), UUID.randomUUID());
        pag2.confirmar(new BigDecimal("30.00"), "NSU-002");
        v2.registrarPagamentoConfirmado(pag2);
        v2.concluir();
        vendaRepository.save(v2);

        jdbcTemplate.update("UPDATE vendas SET data_hora_abertura = ?, data_hora_conclusao = ? WHERE id = ?",
                agoraHora, agoraHora, v1.getId());
        jdbcTemplate.update("UPDATE vendas SET data_hora_abertura = ?, data_hora_conclusao = ? WHERE id = ?",
                agoraHora.plusMinutes(30), agoraHora.plusMinutes(30), v2.getId());

        // Lançamentos no ledger para que a projeção CQRS reflita os pagamentos
        jdbcTemplate.update("""
                INSERT INTO lancamentos_financeiros (venda_id, referencia, valor, tipo, data_hora)
                VALUES (?, ?, ?, 'CREDITO', ?), (?, ?, ?, 'CREDITO', ?)
                """, v1.getId(), UUID.randomUUID(), new BigDecimal("150.00"), agoraHora,
                v2.getId(), UUID.randomUUID(), new BigDecimal("30.00"), agoraHora.plusMinutes(30));

        // Aplica projeção diária a partir do ledger
        catchUpJob.executarBackfillCompleto();

        // Executa legado dentro de transação (para evitar o bug F5 do legado)
        DashboardStats legacyStats = new TransactionTemplate(transactionManager)
                .execute(status -> legacyAdapter.toDto(legacyService.getStats()));

        // Executa modular FORA de transação (comprovando robustez de leitura)
        DashboardStats modularStats = mapper.toDto(modularQueryService.consultar());

        ParityComparisonResult result = parityComparator.compareAndEmitParity(legacyStats, modularStats);

        // 1. Métricas não financeiras 100% iguais
        assertThat(result.nonFinancialEqual()).isTrue();
        assertThat(modularStats.salesCount()).isEqualTo(legacyStats.salesCount()).isEqualTo(2L);
        assertThat(modularStats.lowStockCount()).isEqualTo(legacyStats.lowStockCount()).isEqualTo(1L);

        // 2. Vendas recentes idênticas em quantidade e apresentação
        assertThat(modularStats.recentSales()).hasSize(legacyStats.recentSales().size()).hasSize(2);
        assertThat(modularStats.recentSales().get(0).productName()).isEqualTo(legacyStats.recentSales().get(0).productName());
        assertThat(modularStats.recentSales().get(1).productName()).isEqualTo(legacyStats.recentSales().get(1).productName());

        // 3. Receita idêntica (regime de caixa sincronizado com competência no cenário sem troco)
        assertThat(modularStats.totalRevenue()).isEqualByComparingTo(legacyStats.totalRevenue()).isEqualByComparingTo(new BigDecimal("180.00"));
    }

    @Test
    @DisplayName("Decisões Q1 e F6: audita delta financeiro esperado entre regime de competência (legado) e regime de caixa com troco (modular)")
    void deveAuditarDeltaFinanceiroEsperadoPorRegimeDeCaixaComTrocoEF6() {
        Produto p = produtoRepository.save(criarProduto("P10", "Ração Premium 15kg", new BigDecimal("180.00"), new BigDecimal("10"), new BigDecimal("5")));

        LocalDate hoje = LocalDate.now();
        LocalDateTime agoraHora = hoje.atTime(16, 0);

        // Venda paga em dinheiro: pedido de 180.00, valor entregue 200.00, troco 20.00 -> retido líquido = 180.00
        Venda v = new Venda();
        v.adicionarItem(p, BigDecimal.ONE);
        v.irParaPagamento();
        Pagamento pag = new Pagamento(v, MetodoPagamento.DINHEIRO, new BigDecimal("180.00"), UUID.randomUUID());
        pag.confirmar(new BigDecimal("200.00"), "NSU-CASH");
        v.registrarPagamentoConfirmado(pag);
        v.concluir();
        vendaRepository.save(v);

        jdbcTemplate.update("UPDATE vendas SET data_hora_abertura = ?, data_hora_conclusao = ? WHERE id = ?",
                agoraHora, agoraHora, v.getId());

        // Decisão Q1: Ledger registra o valor efetivamente retido em caixa (180.00), e não os 200.00 brutos entregues
        jdbcTemplate.update("""
                INSERT INTO lancamentos_financeiros (venda_id, referencia, valor, tipo, data_hora)
                VALUES (?, ?, ?, 'CREDITO', ?)
                """, v.getId(), UUID.randomUUID(), new BigDecimal("180.00"), agoraHora);

        catchUpJob.executarBackfillCompleto();

        DashboardStats legacyStats = new TransactionTemplate(transactionManager)
                .execute(status -> legacyAdapter.toDto(legacyService.getStats()));
        DashboardStats modularStats = mapper.toDto(modularQueryService.consultar());

        ParityComparisonResult result = parityComparator.compareAndEmitParity(legacyStats, modularStats);

        // As métricas não financeiras continuam estritamente idênticas
        assertThat(result.nonFinancialEqual()).isTrue();
        assertThat(modularStats.salesCount()).isEqualTo(1L);
        assertThat(modularStats.recentSales()).hasSize(1);
        assertThat(modularStats.totalRevenue()).isEqualByComparingTo(new BigDecimal("180.00"));

        // Auditoria de métricas no Micrometer
        assertThat(meterRegistry.find("dashboard.parity.delta").tag("campo", "salesCount").summary().count()).isGreaterThanOrEqualTo(1);
        assertThat(meterRegistry.find("dashboard.parity.delta").tag("campo", "totalRevenue").summary().count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Decisão Q3: audita que vendas abertas aparecem no legado mas são filtradas por status faturado no modular")
    void deveAuditarFiltroQ3OndeLegadoExibeVendasAbertasMasModularFiltraPorStatusFaturadas() {
        // Venda 1: ABERTA (sem itens ou com itens, não concluída)
        Venda vAberta = new Venda();
        vendaRepository.save(vAberta);
        jdbcTemplate.update("UPDATE vendas SET data_hora_abertura = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(5), vAberta.getId());

        DashboardStats legacyStats = new TransactionTemplate(transactionManager)
                .execute(status -> legacyAdapter.toDto(legacyService.getStats()));
        DashboardStats modularStats = mapper.toDto(modularQueryService.consultar());

        // Legado incluiu vAberta porque não filtra por status em findTop5ByOrderByDataHoraAberturaDesc
        assertThat(legacyStats.recentSales()).hasSize(1);
        assertThat(legacyStats.recentSales().get(0).productName()).isEqualTo("Venda sem itens");

        // Modular filtra estritamente por StatusVenda.FATURADAS (Q3: CONCLUIDA, CANCELAMENTO_SOLICITADO)
        assertThat(modularStats.recentSales()).isEmpty();

        // Parity comparator registra essa diferença estrutural esperada
        ParityComparisonResult result = parityComparator.compareAndEmitParity(legacyStats, modularStats);
        assertThat(result.recentSalesCountDelta()).isEqualTo(1L);
        assertThat(result.nonFinancialEqual()).isTrue();
    }

    @Test
    @DisplayName("Resolução de F5 na engine modular: getModularStats() fora de transação executa com sucesso sem LazyInitializationException")
    void deveExecutarComSucessoEmModoModularSemLazyInitializationException() throws Exception {
        Produto produto = produtoRepository.save(
                criarProduto("COD-MOD", "Ração Golden Especial", new BigDecimal("189.90"), new BigDecimal("20"), new BigDecimal("5"))
        );

        Venda venda = new Venda();
        venda.adicionarItem(produto, BigDecimal.ONE);
        venda.irParaPagamento();
        Pagamento pagamento = new Pagamento(venda, MetodoPagamento.DINHEIRO, new BigDecimal("189.90"), UUID.randomUUID());
        pagamento.confirmar(new BigDecimal("189.90"), "NSU-001");
        venda.registrarPagamentoConfirmado(pagamento);
        venda.concluir();
        vendaRepository.save(venda);

        LocalDateTime agora = LocalDateTime.now();
        jdbcTemplate.update("UPDATE vendas SET data_hora_abertura = ?, data_hora_conclusao = ? WHERE id = ?",
                agora, agora, venda.getId());

        jdbcTemplate.update("""
                INSERT INTO lancamentos_financeiros (venda_id, referencia, valor, tipo, data_hora)
                VALUES (?, ?, ?, 'CREDITO', ?)
                """, venda.getId(), UUID.randomUUID(), new BigDecimal("189.90"), agora);
        catchUpJob.executarBackfillCompleto();

        // Execução direta do método modular do provider:
        // Na E0, a chamada fora de transação falhava com LazyInitializationException (achado F5).
        // Aqui, JdbcVendaQueryService usa lateral join direto e executa sem LazyInitializationException!
        DashboardStats stats = statsProvider.getModularStats();

        assertThat(stats).isNotNull();
        assertThat(stats.salesCount()).isEqualTo(1L);
        assertThat(stats.recentSales()).hasSize(1);
        assertThat(stats.recentSales().getFirst().productName()).isEqualTo("Ração Golden Especial");
        assertThat(stats.meta()).isNotNull();
        assertThat(stats.meta().cardsDegradados()).isEmpty();
        assertThat(stats.meta().frescor()).isNotNull();
        assertThat(stats.meta().frescor().emDia()).isTrue();
    }

    @Test
    @DisplayName("Modo Shadow via HTTP: endpoint responde imediatamente com o legado e dispara reconciliação em background")
    void deveResponderViaHttpEmModoShadow() throws Exception {
        // GET /dashboard/stats via MockMvc com dashboard.engine=shadow
        MvcResult result = mockMvc.perform(get("/dashboard/stats")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesCount").value(0))
                .andExpect(jsonPath("$.lowStockCount").value(0))
                .andReturn();

        String json = result.getResponse().getContentAsString();
        DashboardStats response = objectMapper.readValue(json, DashboardStats.class);

        assertThat(response).isNotNull();
        assertThat(response.salesCount()).isEqualTo(0L);

        // Aguarda a conclusão da tarefa de shadow mode em background antes de concluir o teste
        boolean concluido = statsProvider.awaitIdleShadow(java.time.Duration.ofSeconds(3));
        assertThat(concluido).isTrue();
    }

    private Produto criarProduto(String codigo, String nome, BigDecimal preco, BigDecimal disponivel, BigDecimal minimo) {
        Produto p = new Produto();
        p.setCodigoBarras(codigo);
        p.setNome(nome);
        p.setPrecoVenda(preco);
        p.setEstoqueDisponivel(disponivel);
        p.setEstoqueReservado(BigDecimal.ZERO);
        p.setEstoqueMinimo(minimo);
        p.setUnidadeMedida(UnidadeMedida.UN);
        return p;
    }
}
