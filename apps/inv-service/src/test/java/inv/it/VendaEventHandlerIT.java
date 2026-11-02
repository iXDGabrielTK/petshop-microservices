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
import inv.finance.domain.model.LancamentoFinanceiro;
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
import inv.finance.infrastructure.messaging.LancamentoRegistradoEvent;
import inv.finance.infrastructure.messaging.VendaPagaEventListener;
import inv.shared.event.VendaPagaEvent;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RecordApplicationEvents
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

    @Autowired
    private VendaPagaEventListener vendaPagaEventListener;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ApplicationEvents applicationEvents;

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

    @Test
    @DisplayName("Cenário 4 (a) / F1 — Colisão determinística em uk_lancamento_referencia deve ser absorvida com sucesso (conclui venda, baixa estoque e mantém 1 lançamento)")
    void colisaoUkLancamentoReferenciaDeveSerAbsorvidaComSucesso() {
        Produto produto = new Produto();
        produto.setNome("Ração Teste Colisão Fin");
        produto.setCodigoBarras("7890000000102");
        produto.setUnidadeMedida(UnidadeMedida.UN);
        produto.setPrecoVenda(new BigDecimal("50.00"));
        produto.setEstoqueDisponivel(new BigDecimal("10.000"));
        produto.setEstoqueReservado(new BigDecimal("0.000"));
        produto.setEstoqueMinimo(new BigDecimal("2.000"));
        produto = produtoRepository.saveAndFlush(produto);

        // Condição: Venda aberta em AGUARDANDO_PAGAMENTO com produto reservado (10.000 iniciais, 2.000 reservados)
        Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(
                new ItemVendaRequest(produto.getId(), new BigDecimal("2.000"))
        )));

        UUID uuidRef = UUID.randomUUID();
        Long pagamentoId = pagamentoService.criarIntencaoPagamento(
                venda.getId(), MetodoPagamento.PIX, new BigDecimal("100.00"), uuidRef);
        pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("100.00"), "NSU-TX-COLISAO");

        // Pré-insere linha em lancamentos_financeiros com o mesmo uuidRef
        jdbcTemplate.update(
                "INSERT INTO lancamentos_financeiros (venda_id, referencia, tipo, valor) VALUES (?, ?, ?, ?)",
                venda.getId(), uuidRef, "CREDITO", new BigDecimal("100.00")
        );

        String payload = """
                {"vendaId":%d,"pagamentoId":%d}
                """.formatted(venda.getId(), pagamentoId);

        // Ação / Gatilho: Invocar vendaEventHandler.handlePagamentoConfirmado(payload)
        // Asserção: Executa sem lançar DataIntegrityViolationException ou UnexpectedRollbackException
        vendaEventHandler.handlePagamentoConfirmado(payload);

        // Asserção: Transação conclui com sucesso
        // 1. Venda transiciona para CONCLUIDA, valor_pago atualizado
        Venda vendaAposSucesso = vendaRepository.findById(venda.getId()).orElseThrow();
        assertThat(vendaAposSucesso.getStatus()).isEqualTo(StatusVenda.CONCLUIDA);
        assertThat(vendaAposSucesso.getValorPago()).isEqualByComparingTo("100.00");
        assertThat(vendaAposSucesso.getDataHoraConclusao()).isNotNull();

        // 2. Produto tem baixa confirmada (8.000 disponível, 0.000 reservado)
        Produto produtoAposSucesso = produtoRepository.findById(produto.getId()).orElseThrow();
        assertThat(produtoAposSucesso.getEstoqueDisponivel()).isEqualByComparingTo("8.000");
        assertThat(produtoAposSucesso.getEstoqueReservado()).isEqualByComparingTo("0.000");

        // 3. Movimentação SAIDA registrada (além da RESERVA inicial)
        List<MovimentacaoEstoque> movimentacoes = movimentacaoRepository.findAll();
        assertThat(movimentacoes).hasSize(2);
        assertThat(movimentacoes).anyMatch(m -> m.getTipo() == TipoMovimentacao.SAIDA && m.getQuantidade().compareTo(new BigDecimal("2.000")) == 0);

        // 4. Apenas o lançamento pré-inserido existe em lancamentos_financeiros com o mesmo uuidRef
        List<LancamentoFinanceiro> lancamentos = lancamentoRepository.findAll();
        assertThat(lancamentos).hasSize(1);
        assertThat(lancamentos.getFirst().getReferencia()).isEqualTo(uuidRef);
        assertThat(lancamentos.getFirst().getValor()).isEqualByComparingTo("100.00");

        // 5. Nenhum LancamentoRegistradoEvent novo deve ter sido emitido pois a referência já existia
        long eventosPublicados = applicationEvents.stream(LancamentoRegistradoEvent.class)
                .filter(e -> e.vendaId().equals(venda.getId()))
                .count();
        assertThat(eventosPublicados).isZero();
    }

    @Test
    @DisplayName("Cenário 4 (b) / F1 — Concorrência no Ledger: 2 threads simultâneas em onVendaPaga devem inserir apenas 1 lançamento e emitir 1 evento")
    void concorrenciaOnVendaPagaDeveInserirApenasUmLancamentoEEmitirUmEvento() throws Exception {
        Produto produto = new Produto();
        produto.setNome("Ração Concorrência Ledger");
        produto.setCodigoBarras("7890000000103");
        produto.setUnidadeMedida(UnidadeMedida.UN);
        produto.setPrecoVenda(new BigDecimal("50.00"));
        produto.setEstoqueDisponivel(new BigDecimal("10.000"));
        produto.setEstoqueReservado(new BigDecimal("0.000"));
        produto.setEstoqueMinimo(new BigDecimal("2.000"));
        produto = produtoRepository.saveAndFlush(produto);

        Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(
                new ItemVendaRequest(produto.getId(), new BigDecimal("2.000"))
        )));

        UUID uuidRef = UUID.randomUUID();
        VendaPagaEvent evento = new VendaPagaEvent(venda.getId(), uuidRef, new BigDecimal("100.00"));

        int numThreads = 2;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(numThreads);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    transactionTemplate.executeWithoutResult(status -> {
                        vendaPagaEventListener.onVendaPaga(evento);
                    });
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = finishLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();

        List<LancamentoFinanceiro> lancamentos = lancamentoRepository.findAll();
        assertThat(lancamentos).hasSize(1);
        assertThat(lancamentos.getFirst().getReferencia()).isEqualTo(uuidRef);
        assertThat(lancamentos.getFirst().getValor()).isEqualByComparingTo("100.00");

        long eventosPublicados = applicationEvents.stream(LancamentoRegistradoEvent.class)
                .filter(e -> e.vendaId().equals(venda.getId()))
                .count();
        assertThat(eventosPublicados).isEqualTo(1);
    }
}
