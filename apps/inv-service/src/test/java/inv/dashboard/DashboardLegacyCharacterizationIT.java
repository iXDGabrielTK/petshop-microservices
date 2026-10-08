package inv.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import inv.shared.domain.MetodoPagamento;
import inv.checkout.domain.model.Pagamento;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.finance.infrastructure.web.dto.DashboardStats;
import inv.finance.usecase.DashboardService;
import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.UnidadeMedida;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import org.hibernate.LazyInitializationException;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de integração e caracterização da implementação legada do Dashboard (DEBT-ARCH-001 / Etapa E0).
 * <p>
 * Executa contra PostgreSQL e Redis reais via Testcontainers.
 * Objetivos:
 * 1. Gravar e validar a resposta do endpoint /dashboard/stats como golden master quando o banco está consistente;
 * 2. Reproduzir e documentar formalmente o achado F5: falha com {@link LazyInitializationException} (HTTP 500)
 *    no endpoint HTTP legada quando há vendas cadastradas, devido ao acesso a {@code v.getItens().isEmpty()}
 *    fora de escopo transacional com {@code spring.jpa.open-in-view=false};
 * 3. Caracterizar a resposta completa de Golden Master com itens carregados (dentro de contexto de sessão).
 */
@Testcontainers
@SpringBootTest(classes = inv.InvServiceApplication.class)
@AutoConfigureMockMvc(addFilters = false)
class DashboardLegacyCharacterizationIT {

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
    private DashboardService dashboardService;

    @Autowired
    private VendaRepository vendaRepository;

    @Autowired
    private ProdutoRepository produtoRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String ACTIVITY_KEY = "system:activity:ts";

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM itens_venda");
        jdbcTemplate.update("DELETE FROM pagamentos");
        jdbcTemplate.update("DELETE FROM movimentacoes_estoque");
        jdbcTemplate.update("DELETE FROM outbox");
        jdbcTemplate.update("DELETE FROM financial_projection_checkpoint");
        jdbcTemplate.update("DELETE FROM financial_projection_venda");
        jdbcTemplate.update("DELETE FROM projection_retry_queue");
        jdbcTemplate.update("DELETE FROM lancamentos_financeiros");
        vendaRepository.deleteAll();
        produtoRepository.deleteAll();

        redisTemplate.delete(ACTIVITY_KEY);
    }

    @Test
    @DisplayName("Golden Master baseline: GET /dashboard/stats deve retornar 200 com métricas corretas de estoque e telemetria Redis quando não há vendas")
    void deveRetornarGoldenMasterQuandoNaoExistemVendas() throws Exception {
        // 1. Setup Produtos: 2 com estoque baixo, 1 normal
        Produto p1 = criarProduto("COD-001", "Ração Premium Filhote 1kg", new BigDecimal("45.00"), new BigDecimal("2"), new BigDecimal("10"));
        Produto p2 = criarProduto("COD-002", "Antipulgas Pipeta", new BigDecimal("80.00"), BigDecimal.ZERO, new BigDecimal("5"));
        Produto p3 = criarProduto("COD-003", "Shampoo Neutro 500ml", new BigDecimal("25.00"), new BigDecimal("50"), new BigDecimal("10"));
        produtoRepository.saveAll(java.util.List.of(p1, p2, p3));

        // 2. Setup Redis ZSET: 3 atividades na última hora + 1 atividade expirada (> 1 hora)
        long agora = Instant.now().toEpochMilli();
        redisTemplate.opsForZSet().add(ACTIVITY_KEY, "session-user-1", agora - 60_000);
        redisTemplate.opsForZSet().add(ACTIVITY_KEY, "session-user-2", agora - 120_000);
        redisTemplate.opsForZSet().add(ACTIVITY_KEY, "session-user-3", agora - 180_000);
        redisTemplate.opsForZSet().add(ACTIVITY_KEY, "session-user-expired", agora - 4_000_000); // 66 min atrás (fora da janela)

        // 3. Execução HTTP real via MockMvc
        MvcResult result = mockMvc.perform(get("/dashboard/stats")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRevenue").value(0))
                .andExpect(jsonPath("$.revenueGrowth").value(0))
                .andExpect(jsonPath("$.salesCount").value(0))
                .andExpect(jsonPath("$.lowStockCount").value(2))
                .andExpect(jsonPath("$.activityCount").value(3)) // apenas os 3 na janela de 1h
                .andExpect(jsonPath("$.recentSales").isArray())
                .andExpect(jsonPath("$.recentSales").isEmpty())
                .andExpect(jsonPath("$.chartData").isArray())
                .andExpect(jsonPath("$.chartData", hasSize(7)))
                .andReturn();

        // 4. Golden Master: validação da desserialização do JSON de resposta
        String responseJson = result.getResponse().getContentAsString();
        DashboardStats stats = objectMapper.readValue(responseJson, DashboardStats.class);

        assertThat(stats.totalRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(stats.revenueGrowth()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(stats.salesCount()).isEqualTo(0L);
        assertThat(stats.lowStockCount()).isEqualTo(2L);
        assertThat(stats.activityCount()).isEqualTo(3L);
        assertThat(stats.recentSales()).isEmpty();
        assertThat(stats.chartData()).hasSize(7);
        assertThat(stats.chartData()).allMatch(c -> c.total().compareTo(BigDecimal.ZERO) == 0);
    }

    @Test
    @DisplayName("Achado F5: GET /dashboard/stats deve falhar com HTTP 500 por LazyInitializationException quando há vendas cadastradas (código legado)")
    void deveReproduzirAchadoF5LazyInitializationExceptionQuandoExistemVendas() throws Exception {
        // 1. Setup Produto e Venda com item persistido no PostgreSQL
        Produto produto = produtoRepository.save(
                criarProduto("COD-100", "Ração Golden Especial 15kg", new BigDecimal("189.90"), new BigDecimal("20"), new BigDecimal("5"))
        );

        Venda venda = new Venda();
        venda.adicionarItem(produto, BigDecimal.ONE);
        venda.irParaPagamento();
        Pagamento pagamento = new Pagamento(venda, MetodoPagamento.DINHEIRO, new BigDecimal("189.90"), UUID.randomUUID());
        pagamento.confirmar(new BigDecimal("189.90"), "NSU-TEST-001");
        venda.registrarPagamentoConfirmado(pagamento);
        venda.concluir();
        vendaRepository.save(venda);

        // 2. Comprovação no nível de serviço: chamada direta fora de @Transactional lança LazyInitializationException
        // Porque DashboardService.getStats() não tem @Transactional e open-in-view=false
        assertThatThrownBy(() -> dashboardService.getStats())
                .isInstanceOf(LazyInitializationException.class)
                .hasMessageContaining("failed to lazily initialize a collection");

        // 3. Comprovação no nível HTTP: GET /dashboard/stats é capturado pelo GlobalExceptionHandler retornando 500
        mockMvc.perform(get("/dashboard/stats")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.title").value("Erro Interno do Servidor"))
                .andExpect(jsonPath("$.detail", containsString("failed to lazily initialize a collection of role: inv.checkout.domain.model.Venda.itens")));

        // 4. Demonstração adicional da raiz do achado F5:
        // Mesmo uma venda sem itens causa LazyInitializationException ao chamar v.getItens().isEmpty() fora de transação
        vendaRepository.deleteAll();
        Venda vendaSemItens = new Venda();
        vendaRepository.save(vendaSemItens);

        assertThatThrownBy(() -> dashboardService.getStats())
                .isInstanceOf(LazyInitializationException.class)
                .hasMessageContaining("failed to lazily initialize a collection of role: inv.checkout.domain.model.Venda.itens");
    }

    @Test
    @DisplayName("Caracterização: valida comportamento esperado do Golden Master com vendas e telemetria quando executado dentro de escopo com sessão ativa")
    void deveCaracterizarComportamentoComItensCarregadosDentroDeTransacao() throws Exception {
        // 1. Setup Produtos
        Produto p1 = produtoRepository.save(
                criarProduto("COD-201", "Ração Seca 10kg", new BigDecimal("120.00"), new BigDecimal("15"), new BigDecimal("5"))
        );
        Produto p2 = produtoRepository.save(
                criarProduto("COD-202", "Biscoito Canino 500g", new BigDecimal("30.00"), new BigDecimal("2"), new BigDecimal("5"))
        );

        // 2. Setup Venda no mês passado (para caracterizar cálculo de crescimento de receita - growth)
        Venda vPassada = new Venda();
        vPassada.adicionarItem(p1, BigDecimal.ONE);
        vPassada.irParaPagamento();
        Pagamento pagPassado = new Pagamento(vPassada, MetodoPagamento.PIX, new BigDecimal("120.00"), UUID.randomUUID());
        pagPassado.confirmar(new BigDecimal("120.00"), "NSU-PASSADO");
        vPassada.registrarPagamentoConfirmado(pagPassado);
        vPassada.concluir();
        vendaRepository.save(vPassada);

        java.time.LocalDateTime dataMesPassado = java.time.LocalDate.now().minusMonths(1).withDayOfMonth(15).atTime(10, 0);
        jdbcTemplate.update("UPDATE vendas SET data_hora_abertura = ?, data_hora_conclusao = ? WHERE id = ?",
                dataMesPassado, dataMesPassado, vPassada.getId());

        // 3. Setup Venda 1 hoje (com 2 itens -> deve exibir sufixo '+ outros')
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

        // 4. Setup Venda 2 hoje (com 1 item -> deve exibir apenas o nome do produto)
        Venda v2 = new Venda();
        v2.adicionarItem(p2, BigDecimal.ONE);
        v2.irParaPagamento();
        Pagamento pag2 = new Pagamento(v2, MetodoPagamento.PIX, new BigDecimal("30.00"), UUID.randomUUID());
        pag2.confirmar(new BigDecimal("30.00"), "NSU-002");
        v2.registrarPagamentoConfirmado(pag2);
        v2.concluir();
        vendaRepository.save(v2);

        // 5. Setup Venda 3 hoje (sem itens, status ABERTA -> deve exibir 'Venda sem itens')
        Venda v3 = new Venda();
        vendaRepository.save(v3);

        // Fixar timestamps para garantir ordenação determinística e formatação exata de RecentSaleDTO.time
        java.time.LocalDate hoje = java.time.LocalDate.now();
        java.time.LocalDateTime horaV1 = hoje.atTime(14, 0);
        java.time.LocalDateTime horaV2 = hoje.atTime(14, 30);
        java.time.LocalDateTime horaV3 = hoje.atTime(15, 0);

        jdbcTemplate.update("UPDATE vendas SET data_hora_abertura = ? WHERE id = ?", horaV1, v1.getId());
        jdbcTemplate.update("UPDATE vendas SET data_hora_abertura = ? WHERE id = ?", horaV2, v2.getId());
        jdbcTemplate.update("UPDATE vendas SET data_hora_abertura = ? WHERE id = ?", horaV3, v3.getId());

        // 6. Setup Redis ZSET: 2 eventos válidos na janela de 1h + 1 evento expirado
        long agora = Instant.now().toEpochMilli();
        redisTemplate.opsForZSet().add(ACTIVITY_KEY, "active-1", agora - 30_000);
        redisTemplate.opsForZSet().add(ACTIVITY_KEY, "active-2", agora - 60_000);
        redisTemplate.opsForZSet().add(ACTIVITY_KEY, "expired-1", agora - 4_000_000);

        // 7. Executa getStats() dentro de escopo transacional para que as coleções lazy sejam inicializadas com sucesso
        DashboardStats stats = new TransactionTemplate(transactionManager).execute(status -> dashboardService.getStats());

        assertThat(stats).isNotNull();
        // Receita somada no período de hoje: 150.00 + 30.00 = 180.00
        assertThat(stats.totalRevenue()).isEqualByComparingTo(new BigDecimal("180.00"));
        // vendasHoje: v1 e v2 concluídas hoje (vPassada foi no mês passado, v3 está ABERTA)
        assertThat(stats.salesCount()).isEqualTo(2L);
        // p2 tem estoque 2 <= estoqueMinimo 5; p1 tem 15 > 5 -> 1 produto com estoque baixo
        assertThat(stats.lowStockCount()).isEqualTo(1L);
        // activityCount: 2 eventos na janela de 1h
        assertThat(stats.activityCount()).isEqualTo(2L);

        // Crescimento de receita (growth):
        // receitaMesAtual = 180.00, receitaMesPassado = 120.00
        // (180.00 - 120.00) / 120.00 * 100 = 50.0000%
        assertThat(stats.revenueGrowth()).isEqualByComparingTo(new BigDecimal("50.00"));

        // Validação determinística das vendas recentes (ordenadas por data_hora_abertura DESC):
        assertThat(stats.recentSales()).hasSize(4);

        // 1ª mais recente: v3 (aberta às 15:00, sem itens)
        DashboardStats.RecentSaleDTO rec0 = stats.recentSales().get(0);
        assertThat(rec0.id()).isEqualTo(v3.getId().toString());
        assertThat(rec0.productName()).isEqualTo("Venda sem itens");
        assertThat(rec0.amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(rec0.time()).isEqualTo("15:00");

        // 2ª mais recente: v2 (aberta às 14:30, 1 item)
        DashboardStats.RecentSaleDTO rec1 = stats.recentSales().get(1);
        assertThat(rec1.id()).isEqualTo(v2.getId().toString());
        assertThat(rec1.productName()).isEqualTo("Biscoito Canino 500g");
        assertThat(rec1.amount()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(rec1.time()).isEqualTo("14:30");

        // 3ª mais recente: v1 (aberta às 14:00, 2 itens)
        DashboardStats.RecentSaleDTO rec2 = stats.recentSales().get(2);
        assertThat(rec2.id()).isEqualTo(v1.getId().toString());
        assertThat(rec2.productName()).isEqualTo("Ração Seca 10kg + outros");
        assertThat(rec2.amount()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(rec2.time()).isEqualTo("14:00");

        // 4ª mais recente: vPassada (mês passado às 10:00)
        DashboardStats.RecentSaleDTO rec3 = stats.recentSales().get(3);
        assertThat(rec3.id()).isEqualTo(vPassada.getId().toString());
        assertThat(rec3.productName()).isEqualTo("Ração Seca 10kg");
        assertThat(rec3.amount()).isEqualByComparingTo(new BigDecimal("120.00"));
        assertThat(rec3.time()).isEqualTo("10:00");

        // Gráfico de 7 dias: 7 dias em ordem cronológica ascendente
        assertThat(stats.chartData()).hasSize(7);
        // Os 6 primeiros dias estão zerados
        for (int i = 0; i < 6; i++) {
            assertThat(stats.chartData().get(i).total()).isEqualByComparingTo(BigDecimal.ZERO);
        }
        // O 7º dia (hoje) acumula a receita de hoje (180.00)
        DashboardStats.ChartDataDTO diaHoje = stats.chartData().get(6);
        assertThat(diaHoje.total()).isEqualByComparingTo(new BigDecimal("180.00"));
        String nomeEsperadoHoje = hoje.getDayOfWeek().getDisplayName(
                java.time.format.TextStyle.SHORT,
                java.util.Locale.of("pt", "BR")
        );
        assertThat(diaHoje.name()).isEqualTo(nomeEsperadoHoje);

        // Golden Master JSON: serialização e integridade
        String jsonOutput = objectMapper.writeValueAsString(stats);
        DashboardStats statsReconstruida = objectMapper.readValue(jsonOutput, DashboardStats.class);
        assertThat(statsReconstruida.totalRevenue()).isEqualByComparingTo(stats.totalRevenue());
        assertThat(statsReconstruida.revenueGrowth()).isEqualByComparingTo(stats.revenueGrowth());
        assertThat(statsReconstruida.salesCount()).isEqualTo(stats.salesCount());
        assertThat(statsReconstruida.lowStockCount()).isEqualTo(stats.lowStockCount());
        assertThat(statsReconstruida.activityCount()).isEqualTo(stats.activityCount());
        assertThat(statsReconstruida.recentSales()).hasSize(4);
        assertThat(statsReconstruida.chartData()).hasSize(7);
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
