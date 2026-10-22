package inv.inventory;

import inv.InvServiceApplication;
import inv.inventory.api.CatalogoQueryService;
import inv.inventory.api.ProdutoCheckoutView;
import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.UnidadeMedida;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(classes = InvServiceApplication.class)
class JpaCatalogoQueryServiceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
        registry.add("finance.projection.diaria.enabled", () -> "false");
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private ConnectionFactory connectionFactory;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CatalogoQueryService catalogoQueryService;

    @Autowired
    private ProdutoRepository produtoRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM itens_venda");
        jdbcTemplate.update("DELETE FROM movimentacoes_estoque");
        produtoRepository.deleteAll();
    }

    @Test
    @DisplayName("Q2 — buscarParaCheckout deve retornar ProdutoCheckoutView dos IDs existentes e omitir IDs inexistentes sem hidratar entidade")
    void deveRetornarViewsExistentesEOmitirInexistentesSemHidratarEntidade() {
        Produto p1 = salvarProduto("7892000000010", "Ração Gatos Adultos 10kg",
                new BigDecimal("149.90"), new BigDecimal("15.000"), new BigDecimal("3.000"));
        Produto p2 = salvarProduto("7892000000027", "Petisco Dental",
                new BigDecimal("12.50"), new BigDecimal("20.000"), new BigDecimal("5.000"));

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        List<ProdutoCheckoutView> views = catalogoQueryService.buscarParaCheckout(
                Set.of(p1.getId(), p2.getId(), 999999L));

        assertThat(views)
                .hasSize(2)
                .containsExactlyInAnyOrder(
                        new ProdutoCheckoutView(p1.getId(), "Ração Gatos Adultos 10kg", new BigDecimal("149.90")),
                        new ProdutoCheckoutView(p2.getId(), "Petisco Dental", new BigDecimal("12.50"))
                );
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1L);
        assertThat(stats.getEntityLoadCount()).isZero();
    }

    @Test
    @DisplayName("Q2/Q9 — buscarParaCheckout não filtra produto sem saldo ou inativo para venda (paridade com findAllById)")
    void deveRetornarProdutoMesmoComSaldoZeroESemEstoqueMinimo() {
        Produto semSaldo = salvarProduto("7892000000034", "Produto Indisponível / Inativo",
                new BigDecimal("59.00"), BigDecimal.ZERO, null);

        List<ProdutoCheckoutView> views = catalogoQueryService.buscarParaCheckout(Set.of(semSaldo.getId()));

        assertThat(views)
                .containsExactly(new ProdutoCheckoutView(semSaldo.getId(), "Produto Indisponível / Inativo", new BigDecimal("59.00")));
    }

    @Test
    @DisplayName("Conjunto vazio deve retornar lista vazia sem disparar query ao banco")
    void conjuntoVazioDeveRetornarListaVaziaSemExecutarQuery() {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        List<ProdutoCheckoutView> views = catalogoQueryService.buscarParaCheckout(Set.of());

        assertThat(views).isEmpty();
        assertThat(stats.getPrepareStatementCount()).isZero();
    }

    @Test
    @DisplayName("buscarParaCheckout deve rejeitar conjunto nulo")
    void deveRejeitarConjuntoNulo() {
        assertThatThrownBy(() -> catalogoQueryService.buscarParaCheckout(null))
                .isInstanceOf(NullPointerException.class);
    }

    private Produto salvarProduto(String codigoBarras, String nome, BigDecimal precoVenda,
                                  BigDecimal disponivel, BigDecimal minimo) {
        Produto produto = new Produto();
        produto.setCodigoBarras(codigoBarras);
        produto.setNome(nome);
        produto.setUnidadeMedida(UnidadeMedida.UN);
        produto.setPrecoVenda(precoVenda);
        produto.setEstoqueDisponivel(disponivel);
        produto.setEstoqueReservado(new BigDecimal("0.000"));
        produto.setEstoqueMinimo(minimo);
        return produtoRepository.saveAndFlush(produto);
    }
}
