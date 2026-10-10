package inv.inventory;

import inv.InvServiceApplication;
import inv.inventory.api.EstoqueQueryService;
import inv.inventory.api.ResumoEstoque;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = InvServiceApplication.class)
class JpaEstoqueQueryServiceIT {

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
        registry.add("dashboard.engine", () -> "legacy");
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private ConnectionFactory connectionFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EstoqueQueryService estoqueQueryService;

    @Autowired
    private ProdutoRepository produtoRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM itens_venda");
        jdbcTemplate.update("DELETE FROM movimentacoes_estoque");
        produtoRepository.deleteAll();
    }

    @Test
    @DisplayName("Índice parcial idx_produtos_abaixo_min da V5 deve estar criado e válido no catálogo do PostgreSQL")
    void deveValidarQueIndiceProdutosAbaixoDoMinimoEstaValido() {
        Boolean indexValido = jdbcTemplate.queryForObject("""
                SELECT idx.indisvalid
                  FROM pg_index idx
                  JOIN pg_class i ON i.oid = idx.indexrelid
                 WHERE i.relname = 'idx_produtos_abaixo_min'
                """, Boolean.class);

        assertThat(indexValido)
                .as("O índice idx_produtos_abaixo_min deve existir e estar válido no PostgreSQL")
                .isTrue();
    }

    @Test
    @DisplayName("resumo() deve retornar contagem correta e ignorar produtos com estoque_minimo NULL (paridade com o legado)")
    void deveContarProdutosAbaixoDoMinimoEIgnorarEstoqueMinimoNulo() {
        // Produto 1: estoque_disponivel (5) < estoque_minimo (10) -> CONTADO
        Produto p1 = criarProduto("COD-101", "Ração Filhote 1kg",
                new BigDecimal("5.000"), new BigDecimal("10.000"));

        // Produto 2: estoque_disponivel (10) == estoque_minimo (10) -> CONTADO (<= minimo)
        Produto p2 = criarProduto("COD-102", "Shampoo Pet 500ml",
                new BigDecimal("10.000"), new BigDecimal("10.000"));

        // Produto 3: estoque_disponivel (15) > estoque_minimo (10) -> NÃO CONTADO
        Produto p3 = criarProduto("COD-103", "Petisco Ossinho",
                new BigDecimal("15.000"), new BigDecimal("10.000"));

        // Produto 4: estoque_disponivel (0) mas estoque_minimo é NULL -> NÃO CONTADO (regra do legado)
        Produto p4 = criarProduto("COD-104", "Produto Sem Mínimo 1",
                BigDecimal.ZERO, null);

        // Produto 5: estoque_disponivel (50) e estoque_minimo é NULL -> NÃO CONTADO
        Produto p5 = criarProduto("COD-105", "Produto Sem Mínimo 2",
                new BigDecimal("50.000"), null);

        produtoRepository.saveAll(List.of(p1, p2, p3, p4, p5));

        ResumoEstoque resumo = estoqueQueryService.resumo();

        assertThat(resumo.produtosAbaixoDoMinimo()).isEqualTo(2);

        // Paridade estrita com o método legado do ProdutoRepository
        long contagemLegada = produtoRepository.countProdutosComEstoqueBaixo();
        assertThat(resumo.produtosAbaixoDoMinimo()).isEqualTo(contagemLegada);
    }

    @Test
    @DisplayName("resumo() deve retornar 0 quando o banco de produtos estiver vazio")
    void deveRetornarZeroQuandoNaoExistemProdutos() {
        ResumoEstoque resumo = estoqueQueryService.resumo();

        assertThat(resumo.produtosAbaixoDoMinimo()).isZero();
    }

    @Test
    @DisplayName("EXPLAIN: countProdutosComEstoqueBaixo deve utilizar o índice parcial idx_produtos_abaixo_min")
    void deveUtilizarIndiceProdutosAbaixoMinNoPlanoDeExecucao() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            String explain = String.join("\n", jdbcTemplate.queryForList("""
                    EXPLAIN SELECT COUNT(p.id) FROM produtos p
                     WHERE p.estoque_disponivel <= p.estoque_minimo
                    """, String.class));

            assertThat(explain)
                    .as("Plano de contagem de produtos abaixo do mínimo deve usar idx_produtos_abaixo_min")
                    .contains("idx_produtos_abaixo_min");
        });
    }

    @Test
    @DisplayName("ResumoEstoque deve rejeitar contagem negativa")
    void deveRejeitarContagemNegativaEmResumoEstoque() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ResumoEstoque(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Produto criarProduto(String codigoBarras, String nome, BigDecimal estoqueDisponivel, BigDecimal estoqueMinimo) {
        Produto p = new Produto();
        p.setCodigoBarras(codigoBarras);
        p.setNome(nome);
        p.setPrecoVenda(new BigDecimal("29.90"));
        p.setUnidadeMedida(UnidadeMedida.UN);
        p.setEstoqueDisponivel(estoqueDisponivel);
        p.setEstoqueReservado(BigDecimal.ZERO);
        p.setEstoqueMinimo(estoqueMinimo);
        return p;
    }
}
