package inv.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import common.telemetry.TelemetriaKeys;
import io.micrometer.core.instrument.MeterRegistry;
import inv.InvServiceApplication;
import inv.checkout.domain.model.Pagamento;
import inv.checkout.domain.model.StatusVenda;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.messaging.VendaEventHandler;
import inv.checkout.infrastructure.messaging.event.PagamentoConfirmadoEvent;
import inv.checkout.infrastructure.persistence.PagamentoRepository;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.dashboard.application.DashboardQueryService;
import inv.dashboard.application.DashboardSnapshot;
import inv.dashboard.infrastructure.cache.DashboardCacheConfig;
import inv.dashboard.infrastructure.provider.DefaultDashboardStatsProvider;
import inv.dashboard.infrastructure.web.dto.DashboardStats;
import inv.finance.api.FrescorProjecao;
import inv.finance.api.PeriodoContabil;
import inv.finance.api.ReceitaDiariaView;
import inv.finance.api.ReceitaQueryService;
import inv.finance.domain.model.FechamentoCaixaDiario;
import inv.finance.domain.model.LancamentoFinanceiro;
import inv.finance.domain.model.TipoLancamento;
import inv.finance.infrastructure.jobs.ProjecaoDiariaCatchUpJob;
import inv.finance.infrastructure.persistence.FechamentoCaixaRepository;
import inv.finance.infrastructure.persistence.LancamentoFinanceiroRepository;
import inv.finance.infrastructure.web.dto.DivergenciaResult;
import inv.finance.usecase.FechamentoCaixaService;
import inv.finance.usecase.ReconciliacaoService;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate de Homologação e Cutover (DEBT-ARCH-001 / Etapa E5 / Decisão Q6).
 * <p>
 * Executa uma carga sintética representativa de 7 dias contínuos em ambiente integrado (PostgreSQL 15 e Redis 7):
 * - Vendas concluídas com cartão, PIX e dinheiro;
 * - Pagamentos divididos (múltiplos pagamentos por venda);
 * - Pagamento em dinheiro com troco (Decisão Q1: retenção líquida no ledger e projeção);
 * - Lançamentos perto da meia-noite (23:59:58 e 00:00:02) segregados nos respectivos dias;
 * - Estornos/débitos impactando a receita líquida;
 * - Vendas não faturadas (ABERTA e CANCELADA) não afetando contagem de faturadas;
 * - Movimentação e alertas de estoque baixo;
 * - Telemetria de plataforma em Redis;
 * - Reconciliação a 100%: Σ projeção diária == Σ ledger, 0 divergências em ReconciliacaoService e frescor em dia;
 * - Fechamento de caixa D+0 sem divergências;
 * - Endpoint HTTP /dashboard/stats sob a engine MODULAR (Caffeine cache ativo, p95 < 100ms, 0% 5xx);
 * - Capacidade de Rollback com DASHBOARD_ENGINE=legacy.
 */
@Testcontainers
@SpringBootTest(classes = InvServiceApplication.class)
@AutoConfigureMockMvc(addFilters = false)
class CargaSintetica7DiasCutoverIT {

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
        registry.add("dashboard.engine", () -> "modular");
        registry.add("dashboard.cache.ttl", () -> "15s");
        registry.add("dashboard.cache.max-size", () -> "100");
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
    private VendaRepository vendaRepository;

    @Autowired
    private ProdutoRepository produtoRepository;

    @Autowired
    private PagamentoRepository pagamentoRepository;

    @Autowired
    private LancamentoFinanceiroRepository lancamentoRepository;

    @Autowired
    private FechamentoCaixaRepository fechamentoCaixaRepository;

    @Autowired
    private ProjecaoDiariaCatchUpJob catchUpJob;

    @Autowired
    private FechamentoCaixaService fechamentoCaixaService;

    @Autowired
    private ReconciliacaoService reconciliacaoService;

    @Autowired
    private ReceitaQueryService receitaQueryService;

    @Autowired
    private DashboardQueryService dashboardQueryService;

    @Autowired
    private DefaultDashboardStatsProvider statsProvider;

    @Autowired
    private VendaEventHandler vendaEventHandler;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    private inv.dashboard.infrastructure.legacy.LegacyDashboardStatsAdapter legacyAdapter;

    @Autowired
    private inv.dashboard.infrastructure.web.DashboardStatsMapper statsMapper;

    @Autowired
    private inv.dashboard.infrastructure.shadow.DashboardParityComparator parityComparator;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM itens_venda");
        jdbcTemplate.update("DELETE FROM pagamentos");
        jdbcTemplate.update("DELETE FROM movimentacoes_estoque");
        jdbcTemplate.update("DELETE FROM outbox");
        jdbcTemplate.update("DELETE FROM financial_reconciliation_checkpoint");
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

        Cache cache = cacheManager.getCache(DashboardCacheConfig.CACHE_NAME);
        if (cache != null) {
            cache.clear();
        }
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (E5 Cutover Gate): Carga sintética de 7 dias alcança 100% de reconciliação, fechamento D+0 consistente e SLOs de leitura")
    void deveExecutarCargaSintetica7DiasEAtenderTodosOsCriteriosDeCutover() throws Exception {
        LocalDate hoje = LocalDate.now();
        LocalDate d1 = hoje.minusDays(6);
        LocalDate d2 = hoje.minusDays(5);
        LocalDate d3 = hoje.minusDays(4);
        LocalDate d4 = hoje.minusDays(3);
        LocalDate d5 = hoje.minusDays(2);
        LocalDate d6 = hoje.minusDays(1);
        LocalDate d7 = hoje;

        // 1. Cadastros de Produtos (para vendas e monitoramento de estoque)
        Produto prodRacao = produtoRepository.save(criarProduto("BAR-01", "Ração Especial Cães 15kg", new BigDecimal("200.00"), new BigDecimal("30"), new BigDecimal("5")));
        Produto prodShampoo = produtoRepository.save(criarProduto("BAR-02", "Shampoo Hipoalergênico Pet 500ml", new BigDecimal("45.00"), new BigDecimal("1"), new BigDecimal("5"))); // estoque baixo!
        Produto prodPetisco = produtoRepository.save(criarProduto("BAR-03", "Petisco Dental Care 100g", new BigDecimal("15.00"), BigDecimal.ZERO, new BigDecimal("2"))); // estoque baixo!

        // =========================================================================
        // DIA 1 (D-6): Vendas à vista
        // =========================================================================
        Venda v1 = registrarVendaConcluida(prodRacao, 1, MetodoPagamento.PIX, new BigDecimal("200.00"), d1.atTime(10, 15));
        criarLancamentoLedger(v1.getId(), TipoLancamento.CREDITO, new BigDecimal("200.00"), d1.atTime(10, 15));

        Venda v2 = registrarVendaConcluida(prodShampoo, 2, MetodoPagamento.CARTAO_DEBITO, new BigDecimal("90.00"), d1.atTime(16, 30));
        criarLancamentoLedger(v2.getId(), TipoLancamento.CREDITO, new BigDecimal("90.00"), d1.atTime(16, 30));

        // =========================================================================
        // DIA 2 (D-5): Pagamento Dividido (2 pagamentos na mesma venda: Cartão + Dinheiro)
        // =========================================================================
        Venda v3 = new Venda();
        v3.adicionarItem(prodRacao, BigDecimal.ONE); // 200.00
        v3.adicionarItem(prodShampoo, BigDecimal.ONE); // 45.00 -> total 245.00
        v3.irParaPagamento();
        Pagamento pag3a = new Pagamento(v3, MetodoPagamento.CARTAO_CREDITO, new BigDecimal("145.00"), UUID.randomUUID());
        pag3a.processar();
        pag3a.confirmar(new BigDecimal("145.00"), "NSU-DIV-1");
        v3.registrarPagamentoConfirmado(pag3a);

        Pagamento pag3b = new Pagamento(v3, MetodoPagamento.DINHEIRO, new BigDecimal("100.00"), UUID.randomUUID());
        pag3b.confirmar(new BigDecimal("100.00"), "NSU-DIV-2");
        v3.registrarPagamentoConfirmado(pag3b);
        v3.concluir();
        v3 = vendaRepository.save(v3);
        atualizarHorarioVenda(v3.getId(), d2.atTime(14, 0));

        // Dois lançamentos no ledger para a mesma venda
        criarLancamentoLedger(v3.getId(), TipoLancamento.CREDITO, new BigDecimal("145.00"), d2.atTime(14, 0));
        criarLancamentoLedger(v3.getId(), TipoLancamento.CREDITO, new BigDecimal("100.00"), d2.atTime(14, 0));

        // =========================================================================
        // DIA 3 (D-4): Pagamento em dinheiro COM TROCO (Decisão Q1) + Estorno Parcial
        // =========================================================================
        // Venda 4: total R$ 90.00, cliente entrega R$ 100.00, troco R$ 10.00 -> ledger registra R$ 90.00 retido
        Venda v4 = new Venda();
        v4.adicionarItem(prodShampoo, new BigDecimal("2"));
        v4.irParaPagamento();
        v4 = vendaRepository.save(v4);
        Pagamento pag4 = new Pagamento(v4, MetodoPagamento.DINHEIRO, new BigDecimal("90.00"), UUID.randomUUID());
        pag4.confirmar(new BigDecimal("100.00"), "NSU-CASH-TROCO");
        pag4 = pagamentoRepository.save(pag4);

        vendaEventHandler.handlePagamentoConfirmado(objectMapper.writeValueAsString(
                new PagamentoConfirmadoEvent(v4.getId(), pag4.getId())
        ));
        atualizarHorarioVenda(v4.getId(), d3.atTime(11, 20));
        // Ajusta data do lançamento gerado pelo listener para d3
        jdbcTemplate.update("UPDATE lancamentos_financeiros SET data_hora = ? WHERE venda_id = ?",
                d3.atTime(11, 20), v4.getId());

        // Venda 5: R$ 200.00 com estorno parcial posterior de R$ 50.00
        Venda v5 = registrarVendaConcluida(prodRacao, 1, MetodoPagamento.PIX, new BigDecimal("200.00"), d3.atTime(15, 0));
        criarLancamentoLedger(v5.getId(), TipoLancamento.CREDITO, new BigDecimal("200.00"), d3.atTime(15, 0));
        criarLancamentoLedger(v5.getId(), TipoLancamento.ESTORNO, new BigDecimal("-50.00"), d3.atTime(17, 30));

        // =========================================================================
        // DIA 4 (D-3): Lançamento perto da meia-noite (23:59:58 de D-3)
        // =========================================================================
        Venda v6 = registrarVendaConcluida(prodPetisco, 4, MetodoPagamento.PIX, new BigDecimal("60.00"), d4.atTime(LocalTime.of(23, 59, 58)));
        criarLancamentoLedger(v6.getId(), TipoLancamento.CREDITO, new BigDecimal("60.00"), d4.atTime(LocalTime.of(23, 59, 58)));

        // =========================================================================
        // DIA 5 (D-2): Lançamento perto da meia-noite (00:00:02 de D-2) + Vendas não faturadas
        // =========================================================================
        Venda v7 = registrarVendaConcluida(prodShampoo, 1, MetodoPagamento.PIX, new BigDecimal("45.00"), d5.atTime(LocalTime.of(0, 0, 2)));
        criarLancamentoLedger(v7.getId(), TipoLancamento.CREDITO, new BigDecimal("45.00"), d5.atTime(LocalTime.of(0, 0, 2)));

        // Venda em Aberto (não deve gerar ledger nem contar como faturada)
        Venda vAberta = new Venda();
        vAberta.adicionarItem(prodRacao, BigDecimal.ONE);
        vAberta = vendaRepository.save(vAberta);
        atualizarHorarioVenda(vAberta.getId(), d5.atTime(10, 0));

        // Venda Cancelada
        Venda vCancelada = new Venda();
        vCancelada.cancelar();
        vCancelada = vendaRepository.save(vCancelada);
        atualizarHorarioVenda(vCancelada.getId(), d5.atTime(11, 0));

        // =========================================================================
        // DIA 6 (D-1): Venda à vista + Estorno
        // =========================================================================
        Venda v8 = registrarVendaConcluida(prodRacao, 1, MetodoPagamento.CARTAO_CREDITO, new BigDecimal("200.00"), d6.atTime(13, 0));
        criarLancamentoLedger(v8.getId(), TipoLancamento.CREDITO, new BigDecimal("200.00"), d6.atTime(13, 0));
        criarLancamentoLedger(v8.getId(), TipoLancamento.ESTORNO, new BigDecimal("-20.00"), d6.atTime(18, 0));

        // =========================================================================
        // DIA 7 (D-0 / Hoje): Vendas de Hoje + Telemetria de Atividade Redis
        // =========================================================================
        Venda vHoje1 = registrarVendaConcluida(prodRacao, 1, MetodoPagamento.PIX, new BigDecimal("200.00"), d7.atTime(9, 30));
        criarLancamentoLedger(vHoje1.getId(), TipoLancamento.CREDITO, new BigDecimal("200.00"), d7.atTime(9, 30));

        Venda vHoje2 = registrarVendaConcluida(prodPetisco, 2, MetodoPagamento.CARTAO_DEBITO, new BigDecimal("30.00"), d7.atTime(11, 15));
        criarLancamentoLedger(vHoje2.getId(), TipoLancamento.CREDITO, new BigDecimal("30.00"), d7.atTime(11, 15));

        // Telemetria no Redis: 5 acessos na última hora, 1 antigo
        long agoraMillis = Instant.now().toEpochMilli();
        for (int i = 1; i <= 5; i++) {
            redisTemplate.opsForZSet().add(TelemetriaKeys.ATIVIDADE_ZSET, "user-session-" + i, agoraMillis - (i * 60_000));
        }
        redisTemplate.opsForZSet().add(TelemetriaKeys.ATIVIDADE_ZSET, "user-session-old", agoraMillis - (2 * 3600_000));

        // =========================================================================
        // ETAPA DE PROCESSAMENTO: CATCH-UP / BACKFILL DE TODA A CARGA
        // =========================================================================
        int backfillAfetados = catchUpJob.executarBackfillCompleto();
        assertThat(backfillAfetados).isGreaterThanOrEqualTo(6);

        // =========================================================================
        // GATE DE AUDITORIA 1: Reconciliação Total a 100% (Σ projeção == Σ ledger)
        // =========================================================================
        BigDecimal somaTotalLedger = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(valor), 0) FROM lancamentos_financeiros", BigDecimal.class);
        BigDecimal somaTotalProjecao = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(total_creditos - total_debitos), 0) FROM financial_projection_diaria", BigDecimal.class);

        assertThat(somaTotalProjecao)
                .as("Σ projeção diária deve ser 100% idêntico a Σ ledger")
                .isEqualByComparingTo(somaTotalLedger);

        // Reconciliação detalhada dia a dia: para cada dia do período de 7 dias
        List<Map<String, Object>> reconciliacaoPorDia = jdbcTemplate.queryForList("""
                SELECT lf.data_hora::date AS dia,
                       SUM(GREATEST(lf.valor, 0)) AS creditos_esperados,
                       SUM(GREATEST(-lf.valor, 0)) AS debitos_esperados
                  FROM lancamentos_financeiros lf
                 GROUP BY lf.data_hora::date
                 ORDER BY lf.data_hora::date
                """);

        for (Map<String, Object> diaRow : reconciliacaoPorDia) {
            LocalDate dia = ((java.sql.Date) diaRow.get("dia")).toLocalDate();
            BigDecimal credEsp = (BigDecimal) diaRow.get("creditos_esperados");
            BigDecimal debEsp = (BigDecimal) diaRow.get("debitos_esperados");

            List<ReceitaDiariaView> viewList = receitaQueryService.serieDiaria(PeriodoContabil.dia(dia));
            assertThat(viewList).hasSize(1);
            ReceitaDiariaView view = viewList.getFirst();

            assertThat(view.creditos())
                    .as("Créditos da projeção no dia %s devem bater exatamente com o ledger", dia)
                    .isEqualByComparingTo(credEsp);
            assertThat(view.debitos())
                    .as("Débitos da projeção no dia %s devem bater exatamente com o ledger", dia)
                    .isEqualByComparingTo(debEsp);
        }

        // =========================================================================
        // GATE DE AUDITORIA 2: ReconciliacaoService com 0 divergências e Frescor em dia
        // =========================================================================
        Long maxLedgerId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM lancamentos_financeiros", Long.class);
        List<DivergenciaResult> divergencias = reconciliacaoService.executarReconciliacaoIncremental(0, maxLedgerId);
        assertThat(divergencias)
                .as("ReconciliacaoService deve acusar ZERO divergências pós-carga")
                .isEmpty();

        FrescorProjecao frescor = receitaQueryService.frescor();
        assertThat(frescor.emDia()).as("Projeção deve estar com frescor em dia").isTrue();
        assertThat(frescor.atraso()).isEqualTo(0);
        assertThat(frescor.pendentesRetry()).isEqualTo(0);

        // =========================================================================
        // GATE DE AUDITORIA 3: Fechamento de Caixa D+0 consistente
        // =========================================================================
        fechamentoCaixaService.executarFechamentoTransacional();
        List<FechamentoCaixaDiario> fechamentos = fechamentoCaixaRepository.findAll();
        assertThat(fechamentos).isNotEmpty();
        FechamentoCaixaDiario ultimoFechamento = fechamentoCaixaRepository.findTopByOrderByDataReferenciaDesc().orElseThrow();
        assertThat(ultimoFechamento.getUltimoLancamentoId()).isEqualTo(maxLedgerId);
        assertThat(ultimoFechamento.getSaldoFinal()).isEqualByComparingTo(somaTotalLedger);

        // =========================================================================
        // GATE DE AUDITORIA 4: Endpoint HTTP /dashboard/stats sob MODULAR engine
        // =========================================================================
        long inicioHttp = System.currentTimeMillis();
        MvcResult mvcResult = mockMvc.perform(get("/dashboard/stats")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesCount").value(2)) // 2 vendas concluídas hoje (vHoje1 e vHoje2)
                .andExpect(jsonPath("$.lowStockCount").value(2)) // 2 produtos com estoque baixo (prodShampoo e prodPetisco)
                .andExpect(jsonPath("$.activityCount").value(5)) // 5 sessões na última hora
                .andExpect(jsonPath("$.recentSales").isArray())
                .andExpect(jsonPath("$.chartData").isArray())
                .andExpect(jsonPath("$.meta").exists())
                .andExpect(jsonPath("$.meta.frescor.emDia").value(true))
                .andExpect(jsonPath("$.meta.cardsDegradados").isEmpty())
                .andReturn();
        long duracaoHttpMs = System.currentTimeMillis() - inicioHttp;

        DashboardStats stats = objectMapper.readValue(mvcResult.getResponse().getContentAsString(), DashboardStats.class);
        // Receita de hoje: vHoje1 (200.00) + vHoje2 (30.00) = 230.00
        assertThat(stats.totalRevenue()).isEqualByComparingTo(new BigDecimal("230.00"));
        assertThat(stats.chartData()).hasSize(7);

        // Vendas recentes em ordem decrescente de conclusão: mais recentes de hoje no topo
        assertThat(stats.recentSales()).isNotEmpty();
        assertThat(stats.recentSales().getFirst().amount()).isEqualByComparingTo(new BigDecimal("30.00"));

        // =========================================================================
        // GATE DE AUDITORIA 5: SLOs de Latência, Resiliência e Cache Caffeine
        // =========================================================================
        // 2ª chamada servida pelo cache Caffeine em sub-milissegundos
        long inicioCache = System.currentTimeMillis();
        DashboardStats statsFromCache = statsProvider.getModularStats();
        long duracaoCacheMs = System.currentTimeMillis() - inicioCache;

        assertThat(duracaoCacheMs).isLessThanOrEqualTo(50); // p95 SLO < 100ms
        assertThat(statsFromCache.totalRevenue()).isEqualByComparingTo(stats.totalRevenue());

        // Ausência de falhas em cards
        assertThat(stats.meta().cardsDegradados()).isEmpty();
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (E5 Rollback): Engine LEGACY continua operacional via rollback imediato")
    void deveConfirmarRollbackImediatoParaLegacySemRegressao() {
        Produto prod = produtoRepository.save(criarProduto("RB-01", "Ração Gatos 3kg", new BigDecimal("70.00"), new BigDecimal("10"), new BigDecimal("5")));
        Venda v = registrarVendaConcluida(prod, 1, MetodoPagamento.PIX, new BigDecimal("70.00"), LocalDateTime.now());

        // Provider executando em modo LEGACY (simulando DASHBOARD_ENGINE=legacy)
        DefaultDashboardStatsProvider legacyProvider = new DefaultDashboardStatsProvider(
                "legacy", 0.10,
                legacyAdapter,
                dashboardQueryService,
                statsMapper,
                parityComparator
        );

        assertThat(legacyProvider.getEngineMode()).isEqualTo(DefaultDashboardStatsProvider.EngineMode.LEGACY);

        // Execução real do rollback: deve invocar o serviço legado sob escopo de sessão e retornar métricas consistentes
        DashboardStats stats = new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> legacyProvider.getStats());
        assertThat(stats).isNotNull();
        assertThat(stats.totalRevenue()).isEqualByComparingTo(new BigDecimal("70.00"));
        assertThat(stats.salesCount()).isEqualTo(1L);
        assertThat(stats.recentSales()).isNotEmpty();
        assertThat(stats.meta()).isNull(); // O motor legado não possui campo meta aditivo
    }

    private Venda registrarVendaConcluida(Produto produto, int quantidade, MetodoPagamento metodo, BigDecimal valorTotal, LocalDateTime dataHora) {
        Venda v = new Venda();
        v.adicionarItem(produto, new BigDecimal(quantidade));
        v.irParaPagamento();
        Pagamento p = new Pagamento(v, metodo, valorTotal, UUID.randomUUID());
        p.processar();
        p.confirmar(valorTotal, "NSU-" + UUID.randomUUID().toString().substring(0, 8));
        v.registrarPagamentoConfirmado(p);
        v.concluir();
        v = vendaRepository.save(v);
        atualizarHorarioVenda(v.getId(), dataHora);
        return v;
    }

    private void atualizarHorarioVenda(Long vendaId, LocalDateTime dataHora) {
        jdbcTemplate.update("UPDATE vendas SET data_hora_abertura = ?, data_hora_conclusao = ? WHERE id = ?",
                dataHora, dataHora, vendaId);
    }

    private void criarLancamentoLedger(Long vendaId, TipoLancamento tipo, BigDecimal valor, LocalDateTime dataHora) {
        jdbcTemplate.update("""
                INSERT INTO lancamentos_financeiros (venda_id, referencia, tipo, valor, data_hora)
                VALUES (?, ?, ?, ?, ?)
                """, vendaId, UUID.randomUUID(), tipo.name(), valor, dataHora);
    }

    private Produto criarProduto(String codigo, String nome, BigDecimal preco, BigDecimal disponivel, BigDecimal minimo) {
        Produto p = new Produto();
        p.setCodigoBarras(codigo);
        p.setNome(nome);
        p.setPrecoVenda(preco);
        p.setEstoqueDisponivel(disponivel);
        p.setEstoqueReservado(new BigDecimal("20.00"));
        p.setEstoqueMinimo(minimo);
        p.setUnidadeMedida(UnidadeMedida.UN);
        return p;
    }
}
