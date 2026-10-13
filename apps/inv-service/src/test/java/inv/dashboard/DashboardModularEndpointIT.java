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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de integração HTTP ponta a ponta do endpoint /dashboard/stats sob a engine MODULAR (DEBT-ARCH-001 E4).
 * Comprova que sob dashboard.engine=modular o endpoint responde 200 OK sem LazyInitializationException (resolvendo F5).
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
        registry.add("dashboard.engine", () -> "modular");
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
    @DisplayName("GET /dashboard/stats sob engine MODULAR deve responder 200 OK sem LazyInitializationException quando há vendas cadastradas")
    void deveResponderComSucessoViaHttpEmModoModularComVendasEItens() throws Exception {
        Produto produto = produtoRepository.save(
                criarProduto("MOD-101", "Ração Super Premium 15kg", new BigDecimal("199.90"), new BigDecimal("15"), new BigDecimal("5"))
        );

        Venda venda = new Venda();
        venda.adicionarItem(produto, BigDecimal.ONE);
        venda.irParaPagamento();
        Pagamento pagamento = new Pagamento(venda, MetodoPagamento.PIX, new BigDecimal("199.90"), UUID.randomUUID());
        pagamento.confirmar(new BigDecimal("199.90"), "NSU-MOD-1");
        venda.registrarPagamentoConfirmado(pagamento);
        venda.concluir();
        vendaRepository.save(venda);

        LocalDateTime agora = LocalDateTime.now();
        jdbcTemplate.update("UPDATE vendas SET data_hora_abertura = ?, data_hora_conclusao = ? WHERE id = ?",
                agora, agora, venda.getId());

        jdbcTemplate.update("""
                INSERT INTO lancamentos_financeiros (venda_id, referencia, valor, tipo, data_hora)
                VALUES (?, ?, ?, 'CREDITO', ?)
                """, venda.getId(), UUID.randomUUID(), new BigDecimal("199.90"), agora);
        catchUpJob.executarBackfillCompleto();

        long agoraMillis = Instant.now().toEpochMilli();
        redisTemplate.opsForZSet().add(TelemetriaKeys.ATIVIDADE_ZSET, "user-session-mod", agoraMillis - 10_000);

        // Execução real via HTTP MockMvc
        MvcResult result = mockMvc.perform(get("/dashboard/stats")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesCount").value(1))
                .andExpect(jsonPath("$.activityCount").value(1))
                .andExpect(jsonPath("$.recentSales[0].productName").value("Ração Super Premium 15kg"))
                .andExpect(jsonPath("$.meta").exists())
                .andExpect(jsonPath("$.meta.geradoEm").isNotEmpty())
                .andExpect(jsonPath("$.meta.frescor.emDia").value(true))
                .andReturn();

        DashboardStats response = objectMapper.readValue(result.getResponse().getContentAsString(), DashboardStats.class);
        assertThat(response).isNotNull();
        assertThat(response.totalRevenue()).isEqualByComparingTo(new BigDecimal("199.90"));
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
