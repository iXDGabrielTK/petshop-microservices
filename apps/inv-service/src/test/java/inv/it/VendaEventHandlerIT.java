package inv.it;

import common.exception.BusinessException;
import inv.InvServiceApplication;
import inv.checkout.domain.model.StatusVenda;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.messaging.VendaEventHandler;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.checkout.infrastructure.web.dto.ItemVendaRequest;
import inv.checkout.infrastructure.web.dto.VendaRequest;
import inv.checkout.usecase.PagamentoService;
import inv.checkout.usecase.VendaService;
import inv.finance.infrastructure.persistence.LancamentoFinanceiroRepository;
import inv.inventory.domain.model.MovimentacaoEstoque;
import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.TipoMovimentacao;
import inv.inventory.domain.model.UnidadeMedida;
import inv.inventory.infrastructure.persistence.MovimentacaoRepository;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import inv.shared.domain.MetodoPagamento;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(classes = InvServiceApplication.class)
class VendaEventHandlerIT {

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

    @Autowired
    private VendaService vendaService;

    @Autowired
    private PagamentoService pagamentoService;

    @Autowired
    private VendaEventHandler vendaEventHandler;

    @Autowired
    private ProdutoRepository produtoRepository;

    @Autowired
    private VendaRepository vendaRepository;

    @Autowired
    private MovimentacaoRepository movimentacaoRepository;

    @Autowired
    private LancamentoFinanceiroRepository lancamentoRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM financial_reconciliation_checkpoint");
        jdbcTemplate.update("DELETE FROM financial_projection_diaria_checkpoint");
        jdbcTemplate.update("DELETE FROM financial_projection_diaria");
        jdbcTemplate.update("DELETE FROM financial_projection_checkpoint_v2");
        jdbcTemplate.update("DELETE FROM financial_projection_checkpoint");
        jdbcTemplate.update("DELETE FROM financial_projection_venda");
        jdbcTemplate.update("DELETE FROM projection_retry_queue");
        jdbcTemplate.update("DELETE FROM fechamento_caixa_diario");
        jdbcTemplate.update("DELETE FROM itens_venda");
        jdbcTemplate.update("DELETE FROM pagamentos");
        jdbcTemplate.update("DELETE FROM movimentacoes_estoque");
        jdbcTemplate.update("DELETE FROM outbox");
        jdbcTemplate.update("DELETE FROM lancamentos_financeiros");
        jdbcTemplate.update("DELETE FROM vendas");
        produtoRepository.deleteAll();
    }

    @Test
    @DisplayName("E0 — Falha na baixa de estoque em VendaEventHandler deve reverter toda a transação (venda, estoque e lançamento)")
    void falhaNaBaixaDeEstoqueDeveReverterTransacaoCompleta() {
        Produto produto = new Produto();
        produto.setNome("Ração Teste Fronteira Transacional");
        produto.setCodigoBarras("7890000000101");
        produto.setUnidadeMedida(UnidadeMedida.UN);
        produto.setPrecoVenda(new BigDecimal("50.00"));
        produto.setEstoqueDisponivel(new BigDecimal("10.000"));
        produto.setEstoqueReservado(new BigDecimal("0.000"));
        produto.setEstoqueMinimo(new BigDecimal("2.000"));
        produto = produtoRepository.saveAndFlush(produto);

        Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(
                new ItemVendaRequest(produto.getId(), new BigDecimal("2.000"))
        )));

        Long pagamentoId = pagamentoService.criarIntencaoPagamento(
                venda.getId(), MetodoPagamento.PIX, new BigDecimal("100.00"), UUID.randomUUID());
        pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("100.00"), "NSU-TX-ROLLBACK");

        // Força a falha da baixa zerando o estoque_reservado via SQL antes do processamento do pagamento
        jdbcTemplate.update("UPDATE produtos SET estoque_reservado = 0.000 WHERE id = ?", produto.getId());

        String payload = """
                {"vendaId":%d,"pagamentoId":%d}
                """.formatted(venda.getId(), pagamentoId);

        assertThatThrownBy(() -> vendaEventHandler.handlePagamentoConfirmado(payload))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(BusinessException.class)
                .rootCause()
                .hasMessage("Inconsistência: Tentativa de baixar mais estoque reservado do que o existente para: " + produto.getNome());

        // Rollback total: venda permanece AGUARDANDO_PAGAMENTO, valor_pago inalterado
        Venda vendaAposFalha = vendaRepository.findById(venda.getId()).orElseThrow();
        assertThat(vendaAposFalha.getStatus()).isEqualTo(StatusVenda.AGUARDANDO_PAGAMENTO);
        assertThat(vendaAposFalha.getValorPago()).isEqualByComparingTo("0.00");
        assertThat(vendaAposFalha.getDataHoraConclusao()).isNull();

        // Nenhuma movimentação de SAIDA foi persistida (apenas a RESERVA da abertura existe)
        List<MovimentacaoEstoque> movimentacoes = movimentacaoRepository.findAll();
        assertThat(movimentacoes).hasSize(1);
        assertThat(movimentacoes.getFirst().getTipo()).isEqualTo(TipoMovimentacao.RESERVA);

        // Nenhum lançamento financeiro novo foi persistido
        assertThat(lancamentoRepository.count()).isZero();
    }
}
