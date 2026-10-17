package inv.checkout;

import inv.InvServiceApplication;
import inv.checkout.api.VendaQueryService;
import inv.checkout.api.VendaRecenteView;
import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.UnidadeMedida;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = InvServiceApplication.class)
class JdbcVendaQueryServiceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("inv_db_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.flyway.postgresql.transactional-lock", () -> "false");
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private ConnectionFactory connectionFactory;

    @Autowired
    private VendaQueryService vendaQueryService;

    @Autowired
    private ProdutoRepository produtoRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM itens_venda");
        jdbcTemplate.update("DELETE FROM pagamentos");
        jdbcTemplate.update("DELETE FROM movimentacoes_estoque");
        jdbcTemplate.update("DELETE FROM outbox");
        jdbcTemplate.update("DELETE FROM financial_projection_checkpoint");
        jdbcTemplate.update("DELETE FROM financial_projection_checkpoint_v2");
        jdbcTemplate.update("DELETE FROM financial_projection_venda");
        jdbcTemplate.update("DELETE FROM projection_retry_queue");
        jdbcTemplate.update("DELETE FROM lancamentos_financeiros");
        jdbcTemplate.update("DELETE FROM vendas");
        produtoRepository.deleteAll();
    }

    @Test
    @DisplayName("Gate E2: Os índices da V5 devem estar criados e válidos no catálogo pg_index")
    void deveValidarIndicesDaV5ValidosNoCatalogoPostgreSQL() {
        List<Map<String, Object>> indices = jdbcTemplate.queryForList("""
                SELECT i.relname, idx.indisvalid
                  FROM pg_index idx
                  JOIN pg_class i ON i.oid = idx.indexrelid
                 WHERE i.relname IN ('idx_vendas_concluidas', 'idx_itens_venda_venda', 'idx_produtos_abaixo_min')
                """);

        assertThat(indices).hasSize(3);
        for (Map<String, Object> idx : indices) {
            assertThat(idx.get("indisvalid"))
                    .as("O índice %s deve estar marcado como válido (indisvalid=true)", idx.get("relname"))
                    .isEqualTo(true);
        }
    }

    @Test
    @DisplayName("listarRecentes deve mapear venda sem itens com produtoDestaque nulo e quantidadeItens zero (corrige F5)")
    void deveListarRecentesComVendaSemItens() {
        LocalDateTime agora = LocalDateTime.now();
        long vendaId = inserirVenda("CONCLUIDA", new BigDecimal("150.00"), agora.minusHours(1), agora);

        List<VendaRecenteView> recentes = vendaQueryService.listarRecentes(10);

        assertThat(recentes).hasSize(1);
        VendaRecenteView view = recentes.getFirst();
        assertThat(view.vendaId()).isEqualTo(vendaId);
        assertThat(view.produtoDestaque()).isNull();
        assertThat(view.quantidadeItens()).isZero();
        assertThat(view.valorTotal()).isEqualByComparingTo("150.00");
        assertThat(view.abertaEm()).isNotNull();
    }

    @Test
    @DisplayName("listarRecentes deve identificar produto destaque do primeiro item e quantidade total de itens sem N+1")
    void deveListarRecentesComVendaDeMultiplosItens() {
        Produto p1 = criarEGravarProduto("COD-01", "Ração Especial Golden 15kg");
        Produto p2 = criarEGravarProduto("COD-02", "Coleira Antipulgas");
        Produto p3 = criarEGravarProduto("COD-03", "Bifinho Pet 60g");

        LocalDateTime abertura = LocalDateTime.now().minusHours(2);
        LocalDateTime conclusao = LocalDateTime.now().minusHours(1);
        long vendaId = inserirVenda("CONCLUIDA", new BigDecimal("320.00"), abertura, conclusao);

        inserirItemVenda(vendaId, p1.getId(), "Ração Especial Golden 15kg", new BigDecimal("250.00"), new BigDecimal("1.000"));
        inserirItemVenda(vendaId, p2.getId(), "Coleira Antipulgas", new BigDecimal("50.00"), new BigDecimal("1.000"));
        inserirItemVenda(vendaId, p3.getId(), "Bifinho Pet 60g", new BigDecimal("20.00"), new BigDecimal("2.000"));

        List<VendaRecenteView> recentes = vendaQueryService.listarRecentes(10);

        assertThat(recentes).hasSize(1);
        VendaRecenteView view = recentes.getFirst();
        assertThat(view.vendaId()).isEqualTo(vendaId);
        assertThat(view.produtoDestaque()).isEqualTo("Ração Especial Golden 15kg");
        assertThat(view.quantidadeItens()).isEqualTo(3);
        assertThat(view.valorTotal()).isEqualByComparingTo("320.00");
    }

    @Test
    @DisplayName("listarRecentes deve ordenar por data_hora_conclusao DESC e aplicar clamping de limites [1, 50]")
    void deveOrdenarPorDataConclusaoDescEAplicarClampingDeLimite() {
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        for (int i = 1; i <= 10; i++) {
            inserirVenda("CONCLUIDA", BigDecimal.valueOf(i * 10), base.plusHours(i), base.plusHours(i));
        }

        // Limite padrão 5
        List<VendaRecenteView> top5 = vendaQueryService.listarRecentes(5);
        assertThat(top5).hasSize(5);
        assertThat(top5.get(0).valorTotal()).isEqualByComparingTo("100.00"); // mais recente
        assertThat(top5.get(4).valorTotal()).isEqualByComparingTo("60.00");

        // Clamping inferior: <= 0 vira 1
        List<VendaRecenteView> clampZero = vendaQueryService.listarRecentes(0);
        assertThat(clampZero).hasSize(1);

        List<VendaRecenteView> clampNegativo = vendaQueryService.listarRecentes(-5);
        assertThat(clampNegativo).hasSize(1);

        // Clamping superior: limite alto respeita as linhas disponíveis e teto de 50
        List<VendaRecenteView> clampAlto = vendaQueryService.listarRecentes(100);
        assertThat(clampAlto).hasSize(10);
    }

    @Test
    @DisplayName("Decisão Q3: listarRecentes e contarVendasConcluidas devem incluir CONCLUIDA e CANCELAMENTO_SOLICITADO, e ignorar outros status")
    void deveFiltrarApenasStatusFaturadas() {
        LocalDateTime base = LocalDateTime.now().minusHours(3);

        inserirVenda("CONCLUIDA", new BigDecimal("100.00"), base, base.plusMinutes(10));
        inserirVenda("CANCELAMENTO_SOLICITADO", new BigDecimal("80.00"), base, base.plusMinutes(20));
        inserirVenda("ABERTA", new BigDecimal("50.00"), base, null);
        inserirVenda("AGUARDANDO_PAGAMENTO", new BigDecimal("60.00"), base, null);
        inserirVenda("CANCELADA", new BigDecimal("70.00"), base, base.plusMinutes(30));

        // 1. listarRecentes
        List<VendaRecenteView> recentes = vendaQueryService.listarRecentes(10);
        assertThat(recentes).hasSize(2);
        assertThat(recentes.stream().map(VendaRecenteView::valorTotal).toList())
                .containsExactly(new BigDecimal("80.00"), new BigDecimal("100.00"));

        // 2. contarVendasConcluidas
        long contagem = vendaQueryService.contarVendasConcluidas(base.minusMinutes(1), base.plusHours(1));
        assertThat(contagem).isEqualTo(2);
    }

    @Test
    @DisplayName("contarVendasConcluidas deve ter precisão exata na borda da meia-noite [inicio, fim)")
    void deveRespeitarPrecisaoExataDeMeiaNoite() {
        LocalDateTime inicioMeiaNoite = LocalDate.of(2026, 10, 8).atStartOfDay();
        LocalDateTime fimMeiaNoite = LocalDate.of(2026, 10, 9).atStartOfDay();

        // Antes da borda: 2026-10-07 23:59:59.999999 -> NÃO entra
        inserirVenda("CONCLUIDA", new BigDecimal("10.00"), inicioMeiaNoite.minusSeconds(1), inicioMeiaNoite.minusNanos(1_000));

        // Exatamente na borda inicial: 2026-10-08 00:00:00 -> ENTRA (inclusivo)
        inserirVenda("CONCLUIDA", new BigDecimal("20.00"), inicioMeiaNoite, inicioMeiaNoite);

        // No meio do dia: 2026-10-08 15:30:00 -> ENTRA
        inserirVenda("CONCLUIDA", new BigDecimal("30.00"), inicioMeiaNoite, inicioMeiaNoite.plusHours(15).plusMinutes(30));

        // Perto do fim do dia: 2026-10-08 23:59:59.999999 -> ENTRA
        inserirVenda("CONCLUIDA", new BigDecimal("40.00"), inicioMeiaNoite, fimMeiaNoite.minusNanos(1_000));

        // Exatamente na borda final: 2026-10-09 00:00:00 -> NÃO entra (exclusivo)
        inserirVenda("CONCLUIDA", new BigDecimal("50.00"), fimMeiaNoite, fimMeiaNoite);

        long contagem = vendaQueryService.contarVendasConcluidas(inicioMeiaNoite, fimMeiaNoite);
        assertThat(contagem).isEqualTo(3);
    }

    @Test
    @DisplayName("EXPLAIN: Queries do JdbcVendaQueryService devem utilizar os índices de suporte da V5")
    void deveUtilizarIndicesDaV5NoPlanoDeExecucao() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            // Desabilita seqscan para forçar o otimizador a avaliar planos de índice mesmo em tabelas pequenas
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");

            // 1. EXPLAIN para contarVendasConcluidas
            String explainContagem = String.join("\n", jdbcTemplate.queryForList("""
                    EXPLAIN SELECT COUNT(*) FROM vendas
                     WHERE status IN ('CONCLUIDA', 'CANCELAMENTO_SOLICITADO')
                       AND data_hora_conclusao >= CURRENT_TIMESTAMP - INTERVAL '1 day'
                       AND data_hora_conclusao < CURRENT_TIMESTAMP
                    """, String.class));

            assertThat(explainContagem)
                    .as("Plano de contagem deve fazer Index Scan ou Bitmap Index Scan usando idx_vendas_concluidas")
                    .contains("idx_vendas_concluidas");

            // 2. EXPLAIN para listarRecentes (query completa, incluindo lateral join e subquery de itens)
            String explainRecentes = String.join("\n", jdbcTemplate.queryForList("""
                    EXPLAIN SELECT v.id AS venda_id,
                           pi.nome_produto_snapshot AS produto_destaque,
                           CAST((SELECT COUNT(*) FROM itens_venda x WHERE x.venda_id = v.id) AS integer) AS quantidade_itens,
                           v.valor_total,
                           v.data_hora_abertura AS aberta_em
                      FROM vendas v
                      LEFT JOIN LATERAL (
                           SELECT i.nome_produto_snapshot FROM itens_venda i
                            WHERE i.venda_id = v.id ORDER BY i.id LIMIT 1) pi ON TRUE
                     WHERE v.status IN ('CONCLUIDA', 'CANCELAMENTO_SOLICITADO')
                     ORDER BY v.data_hora_conclusao DESC
                     LIMIT 5
                    """, String.class));

            assertThat(explainRecentes)
                    .as("Plano de listarRecentes deve usar idx_vendas_concluidas para filtrar e ordenar vendas")
                    .contains("idx_vendas_concluidas");

            assertThat(explainRecentes)
                    .as("Plano de listarRecentes deve usar idx_itens_venda_venda para indexar os itens por venda_id")
                    .contains("idx_itens_venda_venda");
        });
    }

    @Test
    @DisplayName("Edge cases: validação de parâmetros nulos e intervalos invertidos nas queries")
    void deveLancarExcecaoOuRetornarZeroEmCasosDeBordaDeDatasEParametrosNulos() {
        LocalDateTime agora = LocalDateTime.now();

        // Parâmetros nulos
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> vendaQueryService.contarVendasConcluidas(null, agora))
                .isInstanceOf(NullPointerException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> vendaQueryService.contarVendasConcluidas(agora, null))
                .isInstanceOf(NullPointerException.class);

        // Intervalo invertido (fim antes de início)
        assertThat(vendaQueryService.contarVendasConcluidas(agora.plusDays(1), agora)).isZero();
    }

    @Test
    @DisplayName("Invariantes: VendaRecenteView deve validar restrições estruturais")
    void deveValidarInvariantesDosRecordsDeView() {
        LocalDateTime agora = LocalDateTime.now();

        // VendaRecenteView: quantidadeItens não pode ser negativa
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new VendaRecenteView(1L, "Ração", -1, BigDecimal.TEN, agora))
                .isInstanceOf(IllegalArgumentException.class);

        // VendaRecenteView: valorTotal e abertaEm não podem ser nulos
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new VendaRecenteView(1L, "Ração", 1, null, agora))
                .isInstanceOf(NullPointerException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new VendaRecenteView(1L, "Ração", 1, BigDecimal.TEN, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Comportamento com banco vazio: queries devem retornar coleções vazias ou zeros sem falhas")
    void deveRetornarValoresPadraoQuandoBancoEstiverCompletamenteVazio() {
        LocalDateTime agora = LocalDateTime.now();

        assertThat(vendaQueryService.listarRecentes(10)).isEmpty();
        assertThat(vendaQueryService.contarVendasConcluidas(agora.minusDays(1), agora)).isZero();
    }

    private long inserirVenda(String status, BigDecimal valorTotal, LocalDateTime abertaEm, LocalDateTime concluidaEm) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO vendas (status, valor_total, data_hora_abertura, data_hora_conclusao, valor_pago, valor_troco, version)
                VALUES (?, ?, ?, ?, ?, 0, 0)
                RETURNING id
                """, Long.class, status, valorTotal, abertaEm, concluidaEm, valorTotal);
    }

    private void inserirItemVenda(long vendaId, long produtoId, String nomeSnapshot, BigDecimal precoSnapshot, BigDecimal qtd) {
        jdbcTemplate.update("""
                INSERT INTO itens_venda (venda_id, produto_id, nome_produto_snapshot, preco_unitario_snapshot, quantidade)
                VALUES (?, ?, ?, ?, ?)
                """, vendaId, produtoId, nomeSnapshot, precoSnapshot, qtd);
    }

    private Produto criarEGravarProduto(String codigoBarras, String nome) {
        Produto p = new Produto();
        p.setCodigoBarras(codigoBarras);
        p.setNome(nome);
        p.setPrecoVenda(new BigDecimal("100.00"));
        p.setUnidadeMedida(UnidadeMedida.UN);
        p.setEstoqueDisponivel(new BigDecimal("10.000"));
        p.setEstoqueReservado(BigDecimal.ZERO);
        p.setEstoqueMinimo(new BigDecimal("5.000"));
        return produtoRepository.save(p);
    }
}

