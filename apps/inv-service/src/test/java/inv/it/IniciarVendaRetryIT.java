package inv.it;

import common.exception.BusinessException;
import inv.InvServiceApplication;
import inv.checkout.domain.model.StatusVenda;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.checkout.infrastructure.web.dto.ItemVendaRequest;
import inv.checkout.infrastructure.web.dto.VendaRequest;
import inv.checkout.usecase.VendaService;
import inv.inventory.api.ComandoEstoqueVenda;
import inv.inventory.api.EstoqueCommandService;
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
import org.springframework.dao.CannotAcquireLockException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Validação de resiliência (Q8): @Retryable e @Recover em VendaService.iniciarVenda
 * sob exceções de contenção de lock pessimista (CannotAcquireLockException / PessimisticLockingFailureException).
 */
@Testcontainers
@SpringBootTest(classes = InvServiceApplication.class)
class IniciarVendaRetryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
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

    @MockitoBean
    private EstoqueCommandService estoqueCommandService;

    @Autowired
    private VendaService vendaService;

    @Autowired
    private ProdutoRepository produtoRepository;

    @Autowired
    private VendaRepository vendaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long produtoId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM itens_venda");
        jdbcTemplate.update("DELETE FROM pagamentos");
        jdbcTemplate.update("DELETE FROM movimentacoes_estoque");
        jdbcTemplate.update("DELETE FROM outbox");
        jdbcTemplate.update("DELETE FROM lancamentos_financeiros");
        jdbcTemplate.update("DELETE FROM vendas");
        produtoRepository.deleteAll();

        Produto p = new Produto();
        p.setNome("Produto Teste Retry Resiliencia");
        p.setCodigoBarras("7899999000011");
        p.setUnidadeMedida(UnidadeMedida.UN);
        p.setPrecoVenda(new BigDecimal("100.00"));
        p.setEstoqueDisponivel(new BigDecimal("50.000"));
        p.setEstoqueReservado(new BigDecimal("0.000"));
        p.setEstoqueMinimo(new BigDecimal("5.000"));
        Produto salvo = produtoRepository.saveAndFlush(p);
        this.produtoId = salvo.getId();
    }

    @Test
    @DisplayName("Q8 — 1ª chamada lança CannotAcquireLockException, 2ª tem sucesso: venda é retornada, mock chamado 2x e venda da tentativa 1 foi revertida")
    void deveRetentarAposLockTimeoutETerSucessoNaSegundaTentativa() {
        doThrow(new CannotAcquireLockException("Deadlock simulated (40P01)"))
                .doNothing()
                .when(estoqueCommandService).reservar(any(ComandoEstoqueVenda.class));

        VendaRequest request = new VendaRequest(List.of(
                new ItemVendaRequest(produtoId, new BigDecimal("1.000"))
        ));

        Venda venda = vendaService.iniciarVenda(request);

        assertThat(venda).isNotNull();
        assertThat(venda.getStatus()).isEqualTo(StatusVenda.AGUARDANDO_PAGAMENTO);

        verify(estoqueCommandService, times(2)).reservar(any(ComandoEstoqueVenda.class));

        // Transação da tentativa 1 sofreu rollback; apenas a venda da tentativa 2 permanece persistida
        assertThat(vendaRepository.count()).isEqualTo(1L);
        Venda vendaNoBanco = vendaRepository.findById(venda.getId()).orElseThrow();
        assertThat(vendaNoBanco.getStatus()).isEqualTo(StatusVenda.AGUARDANDO_PAGAMENTO);
    }

    @Test
    @DisplayName("Q8 — Esgotadas as 3 tentativas com falha de lock, @Recover lança BusinessException com mensagem amigável e reverte tudo")
    void deveAcionarRecoverAposEsgotarRetriesComLockException() {
        doThrow(new CannotAcquireLockException("Lock failure persistent"))
                .when(estoqueCommandService).reservar(any(ComandoEstoqueVenda.class));

        VendaRequest request = new VendaRequest(List.of(
                new ItemVendaRequest(produtoId, new BigDecimal("2.000"))
        ));

        assertThatThrownBy(() -> vendaService.iniciarVenda(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Alta concorrência nos itens selecionados. O estoque foi atualizado por outro caixa. Por favor, tente finalizar novamente.");

        // 3 tentativas foram disparadas pelo @Retryable
        verify(estoqueCommandService, times(3)).reservar(any(ComandoEstoqueVenda.class));

        // Todas as tentativas sofreram rollback; 0 vendas persistidas
        assertThat(vendaRepository.count()).isZero();
    }
}
