package inv.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import common.telemetry.TelemetriaKeys;
import inv.checkout.domain.model.Pagamento;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.dashboard.infrastructure.web.dto.DashboardStats;
import inv.finance.infrastructure.jobs.ProjecaoDiariaCatchUpJob;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de integração HTTP ponta a ponta do endpoint /dashboard/stats no módulo inv.dashboard (DEBT-ARCH-001 E6).
 * Valida o baseline sem vendas, resolução do achado F5 (open-in-view=false sem LazyInitializationException),
 * regime de caixa líquido de troco (Q1) e filtragem por StatusVenda.FATURADAS (Q3).
 */
@Testcontainers
@SpringBootTest(classes = inv.InvServiceApplication.class)
@AutoConfigureMockMvc(addFilters = false)
class DashboardModularEndpointIT {

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
    private ProjecaoDiariaCatchUpJob catchUpJob;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private org.springframework.cache.CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        org.springframework.cache.Cache cache = cacheManager.getCache(inv.dashboard.infrastructure.cache.DashboardCacheConfig.CACHE_NAME);
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

    @Test
    @DisplayName("Baseline: GET /dashboard/stats deve retornar 200 com métricas de estoque e telemetria Redis quando não há vendas")
    void deveRetornarMetricasDeEstoqueETelemetriaQuandoNaoExistemVendas() throws Exception {
        Produto p1 = criarProduto("COD-001", "Ração Premium Filhote 1kg", new BigDecimal("45.00"), new BigDecimal("2"), new BigDecimal("10"));
        Produto p2 = criarProduto("COD-002", "Antipulgas Pipeta", new BigDecimal("80.00"), BigDecimal.ZERO, new BigDecimal("5"));
        Produto p3 = criarProduto("COD-003", "Shampoo Neutro 500ml", new BigDecimal("25.00"), new BigDecimal("50"), new BigDecimal("10"));
        produtoRepository.saveAll(List.of(p1, p2, p3));

        long agora = Instant.now().toEpochMilli();
        redisTemplate.opsForZSet().add(TelemetriaKeys.ATIVIDADE_ZSET, "session-user-1", agora - 60_000);
        redisTemplate.opsForZSet().add(TelemetriaKeys.ATIVIDADE_ZSET, "session-user-2", agora - 120_000);
        redisTemplate.opsForZSet().add(TelemetriaKeys.ATIVIDADE_ZSET, "session-user-3", agora - 180_000);
        redisTemplate.opsForZSet().add(TelemetriaKeys.ATIVIDADE_ZSET, "session-user-expired", agora - 4_000_000);

        mockMvc.perform(get("/dashboard/stats")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRevenue").value(0))
                .andExpect(jsonPath("$.revenueGrowth").value(0))
                .andExpect(jsonPath("$.salesCount").value(0))
                .andExpect(jsonPath("$.lowStockCount").value(2))
                .andExpect(jsonPath("$.activityCount").value(3))
                .andExpect(jsonPath("$.recentSales").isEmpty())
                .andExpect(jsonPath("$.chartData", hasSize(7)));
    }

    @Test
    @DisplayName("GET /dashboard/stats deve responder 200 OK sem LazyInitializationException (F5), filtrar vendas abertas (Q3) e formatar itens")
    void deveResponderComSucessoViaHttpEmModoModularComVendasEItens() throws Exception {
        Produto p1 = produtoRepository.save(
                criarProduto("MOD-101", "Ração Super Premium 15kg", new BigDecimal("199.90"), new BigDecimal("15"), new BigDecimal("5"))
        );
        Produto p2 = produtoRepository.save(
                criarProduto("MOD-102", "Biscoito Canino 500g", new BigDecimal("30.00"), new BigDecimal("2"), new BigDecimal("5"))
        );

        // Venda 1: 2 itens (deve exibir sufixo '+ outros')
        Venda v1 = new Venda();
        v1.adicionarItem(p1.getId(), p1.getNome(), p1.getPrecoVenda(), BigDecimal.ONE);
        v1.adicionarItem(p2.getId(), p2.getNome(), p2.getPrecoVenda(), BigDecimal.ONE);
        v1.irParaPagamento();
        Pagamento pag1 = new Pagamento(v1, MetodoPagamento.PIX, new BigDecimal("229.90"), UUID.randomUUID());
        pag1.confirmar(new BigDecimal("229.90"), "NSU-MOD-1");
        v1.registrarPagamentoConfirmado(pag1);
        v1.concluir();
        vendaRepository.save(v1);

        // Venda 2: ABERTA (Q3: deve ser ignorada nas vendas recentes e na contagem)
        Venda vAberta = new Venda();
        vAberta.adicionarItem(p1.getId(), p1.getNome(), p1.getPrecoVenda(), BigDecimal.ONE);
        vendaRepository.save(vAberta);

        LocalDateTime agora = LocalDateTime.now();
        jdbcTemplate.update("UPDATE vendas SET data_hora_abertura = ?, data_hora_conclusao = ? WHERE id = ?",
                agora, agora, v1.getId());

        jdbcTemplate.update("""
                INSERT INTO lancamentos_financeiros (venda_id, referencia, valor, tipo, data_hora)
                VALUES (?, ?, ?, 'CREDITO', ?)
                """, v1.getId(), UUID.randomUUID(), new BigDecimal("229.90"), agora);
        catchUpJob.executarBackfillCompleto();

        long agoraMillis = Instant.now().toEpochMilli();
        redisTemplate.opsForZSet().add(TelemetriaKeys.ATIVIDADE_ZSET, "user-session-mod", agoraMillis - 10_000);

        MvcResult result = mockMvc.perform(get("/dashboard/stats")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesCount").value(1))
                .andExpect(jsonPath("$.lowStockCount").value(1))
                .andExpect(jsonPath("$.activityCount").value(1))
                .andExpect(jsonPath("$.recentSales", hasSize(1)))
                .andExpect(jsonPath("$.recentSales[0].productName").value("Ração Super Premium 15kg + outros"))
                .andExpect(jsonPath("$.meta").exists())
                .andExpect(jsonPath("$.meta.geradoEm").isNotEmpty())
                .andExpect(jsonPath("$.meta.frescor.emDia").value(true))
                .andReturn();

        DashboardStats response = objectMapper.readValue(result.getResponse().getContentAsString(), DashboardStats.class);
        assertThat(response).isNotNull();
        assertThat(response.totalRevenue()).isEqualByComparingTo(new BigDecimal("229.90"));
        assertThat(response.meta().cardsDegradados()).isEmpty();
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

