package inv.it;

import inv.InvServiceApplication;
import inv.checkout.domain.model.StatusVenda;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.messaging.VendaEventHandler;
import inv.checkout.infrastructure.persistence.PagamentoRepository;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.checkout.infrastructure.web.dto.ItemVendaRequest;
import inv.checkout.infrastructure.web.dto.VendaRequest;
import inv.checkout.usecase.CancelamentoVendaConflitoException;
import inv.checkout.usecase.PagamentoService;
import inv.checkout.usecase.VendaService;
import inv.inventory.api.ComandoEstoqueVenda;
import inv.inventory.api.EstoqueCommandService;
import inv.inventory.api.ItemComando;
import inv.inventory.domain.model.MovimentacaoEstoque;
import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.TipoMovimentacao;
import inv.inventory.domain.model.UnidadeMedida;
import inv.inventory.infrastructure.persistence.MovimentacaoRepository;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import inv.shared.config.RabbitMQConfig;
import inv.shared.domain.MetodoPagamento;
import inv.shared.outbox.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

/**
 * Suíte de concorrência multi-item e devolução (Testes 1 a 4 do DEBT-ARCH-003 / E4).
 * Valida Q2 a Q5, F6 e a ausência física de deadlocks (40P01) no PostgreSQL sob alta concorrência.
 */
@Testcontainers
@SpringBootTest(classes = InvServiceApplication.class)
class DevolucaoConcorrenciaDeadlockIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withCommand("postgres", "-c", "deadlock_timeout=100ms");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "50");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("features.cancelamento-venda-concluida.enabled", () -> "true");
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

    @MockitoSpyBean
    private MovimentacaoRepository movimentacaoRepository;

    @Autowired
    private VendaService vendaService;

    @Autowired
    private PagamentoService pagamentoService;

    @Autowired
    private EstoqueCommandService estoqueCommandService;

    @Autowired
    private VendaEventHandler vendaEventHandler;

    @Autowired
    private ProdutoRepository produtoRepository;

    @Autowired
    private VendaRepository vendaRepository;

    @Autowired
    private PagamentoRepository pagamentoRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        Mockito.reset(movimentacaoRepository);
        limparBanco();
        vendaService.setCancelamentoConcluidoHabilitado(true);
    }

    private void limparBanco() {
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
    @DisplayName("Teste 1 (Q3, Q4) — 40 cancelamentos multi-item invertidos e com itens repetidos executam com 0 deadlocks")
    void cancelamentosMultiItemInvertidosEComItensRepetidosNaoCausamDeadlock() throws Exception {
        Produto prodA = salvarProduto("7891000100010", "Produto Deadlock Cancel A",
                new BigDecimal("25.00"), new BigDecimal("100.000"), new BigDecimal("5.000"));
        Produto prodB = salvarProduto("7891000100020", "Produto Deadlock Cancel B",
                new BigDecimal("35.00"), new BigDecimal("100.000"), new BigDecimal("5.000"));

        List<Long> vendasParaCancelar = new ArrayList<>();

        // 20 vendas com ordem direta [A: 1.000, B: 2.000]
        for (int i = 0; i < 20; i++) {
            Long vId = criarEConcluirVenda(List.of(
                    item(prodA.getId(), "1.000"),
                    item(prodB.getId(), "2.000")
            ));
            vendasParaCancelar.add(vId);
        }

        // 20 vendas com ordem invertida e itens repetidos [B: 1.000, A: 1.000, B: 1.000] (total B: 2.000, A: 1.000)
        for (int i = 0; i < 20; i++) {
            Long vId = criarEConcluirVenda(List.of(
                    item(prodB.getId(), "1.000"),
                    item(prodA.getId(), "1.000"),
                    item(prodB.getId(), "1.000")
            ));
            vendasParaCancelar.add(vId);
        }

        // Estado pré-cancelamento: 40 baixas confirmadas (A: 40.000 baixado -> 60.000 disp; B: 80.000 baixado -> 20.000 disp)
        Produto prodACheck = produtoRepository.findById(prodA.getId()).orElseThrow();
        Produto prodBCheck = produtoRepository.findById(prodB.getId()).orElseThrow();
        assertThat(prodACheck.getEstoqueDisponivel()).isEqualByComparingTo("60.000");
        assertThat(prodACheck.getEstoqueReservado()).isEqualByComparingTo("0.000");
        assertThat(prodBCheck.getEstoqueDisponivel()).isEqualByComparingTo("20.000");
        assertThat(prodBCheck.getEstoqueReservado()).isEqualByComparingTo("0.000");

        // Limpa outbox residual gerado na criação para aferir apenas eventos de cancelamento
        outboxRepository.deleteAll();

        int totalThreads = vendasParaCancelar.size();
        CyclicBarrier barreira = new CyclicBarrier(totalThreads);
        AtomicInteger sucessos = new AtomicInteger();
        Queue<Throwable> erros = new ConcurrentLinkedQueue<>();

        long deadlocksServidorInicio = obterDeadlocksServidor();

        try (ExecutorService pool = Executors.newFixedThreadPool(totalThreads)) {
            for (Long vendaId : vendasParaCancelar) {
                pool.submit(() -> {
                    try {
                        barreira.await(10, TimeUnit.SECONDS);
                        vendaService.cancelarVendaConcluida(vendaId);
                        sucessos.incrementAndGet();
                    } catch (Throwable t) {
                        erros.add(t);
                    }
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(45, TimeUnit.SECONDS)).isTrue();
        }

        long deadlocksServidorFim = obterDeadlocksServidor();
        assertThat(deadlocksServidorFim - deadlocksServidorInicio)
                .withFailMessage("O servidor PostgreSQL registrou deadlocks durante os cancelamentos multi-item!")
                .isZero();

        assertThat(erros).isEmpty();
        assertThat(sucessos.get()).isEqualTo(40);

        // Estoques restaurados ao saldo inicial
        Produto prodAFim = produtoRepository.findById(prodA.getId()).orElseThrow();
        Produto prodBFim = produtoRepository.findById(prodB.getId()).orElseThrow();
        assertThat(prodAFim.getEstoqueDisponivel()).isEqualByComparingTo("100.000");
        assertThat(prodAFim.getEstoqueReservado()).isEqualByComparingTo("0.000");
        assertThat(prodBFim.getEstoqueDisponivel()).isEqualByComparingTo("100.000");
        assertThat(prodBFim.getEstoqueReservado()).isEqualByComparingTo("0.000");

        // 80 movimentações DEVOLUCAO (1 por (vendaId, produtoId) em 40 vendas com 2 produtos cada)
        long totalDevolucoes = movimentacaoRepository.findAll().stream()
                .filter(m -> m.getTipo() == TipoMovimentacao.DEVOLUCAO)
                .count();
        assertThat(totalDevolucoes).isEqualTo(80L);

        // 40 eventos venda.estornada gravados na tabela outbox
        long totalOutboxEstorno = outboxRepository.findAll().stream()
                .filter(o -> RabbitMQConfig.ROUTING_KEY_VENDA_ESTORNADA.equals(o.getRoutingKey()))
                .count();
        assertThat(totalOutboxEstorno).isEqualTo(40L);
    }

    @Test
    @DisplayName("Teste 2 (F6, Q2, Q5) — Dois cancelamentos simultâneos da mesma venda concluída geram 1 sucesso e 1 HTTP 409")
    void doisCancelamentosSimultaneosMesmaVendaConcluidaGeraApenasUmSucessoEUmConflito409() throws Exception {
        Produto p1 = salvarProduto("7891000200010", "Produto Conflito 1",
                new BigDecimal("50.00"), new BigDecimal("20.000"), new BigDecimal("2.000"));
        Produto p2 = salvarProduto("7891000200020", "Produto Conflito 2",
                new BigDecimal("60.00"), new BigDecimal("20.000"), new BigDecimal("2.000"));

        Long vendaId = criarEConcluirVenda(List.of(
                item(p1.getId(), "3.000"),
                item(p2.getId(), "4.000")
        ));

        // Pós-baixa: p1 = 17.000, p2 = 16.000
        assertThat(produtoRepository.findById(p1.getId()).orElseThrow().getEstoqueDisponivel())
                .isEqualByComparingTo("17.000");
        assertThat(produtoRepository.findById(p2.getId()).orElseThrow().getEstoqueDisponivel())
                .isEqualByComparingTo("16.000");

        outboxRepository.deleteAll();

        CyclicBarrier barreira = new CyclicBarrier(2);
        AtomicInteger sucessos = new AtomicInteger();
        Queue<Throwable> conflitos = new ConcurrentLinkedQueue<>();
        Queue<Throwable> errosInesperados = new ConcurrentLinkedQueue<>();

        long deadlocksServidorInicio = obterDeadlocksServidor();

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try {
                        barreira.await(10, TimeUnit.SECONDS);
                        vendaService.cancelarVendaConcluida(vendaId);
                        sucessos.incrementAndGet();
                    } catch (CancelamentoVendaConflitoException e) {
                        conflitos.add(e);
                    } catch (Throwable t) {
                        errosInesperados.add(t);
                    }
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        long deadlocksServidorFim = obterDeadlocksServidor();
        assertThat(deadlocksServidorFim - deadlocksServidorInicio).isZero();

        assertThat(errosInesperados).isEmpty();
        assertThat(sucessos.get()).isEqualTo(1);
        assertThat(conflitos).hasSize(1);

        // Saldos finais recompostos exatamente uma única vez
        assertThat(produtoRepository.findById(p1.getId()).orElseThrow().getEstoqueDisponivel())
                .isEqualByComparingTo("20.000");
        assertThat(produtoRepository.findById(p2.getId()).orElseThrow().getEstoqueDisponivel())
                .isEqualByComparingTo("20.000");

        // 2 movimentações DEVOLUCAO no total para esta venda
        List<MovimentacaoEstoque> devolucoes = movimentacaoRepository.findAll().stream()
                .filter(m -> m.getTipo() == TipoMovimentacao.DEVOLUCAO && vendaId.equals(m.getVendaId()))
                .toList();
        assertThat(devolucoes).hasSize(2);

        // 1 única linha no outbox
        long outboxEstorno = outboxRepository.findAll().stream()
                .filter(o -> RabbitMQConfig.ROUTING_KEY_VENDA_ESTORNADA.equals(o.getRoutingKey()))
                .count();
        assertThat(outboxEstorno).isEqualTo(1L);
    }

    @Test
    @DisplayName("Teste 3 (Q5) — Duas chamadas simultâneas a devolverEstoque sem lock de Checkout colidem no índice V8 com 23505")
    void duasChamadasSimultaneasDevolverEstoqueSemLockCheckoutColideNoIndiceV8Com23505() throws Exception {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        long deadlocksServidorInicio = obterDeadlocksServidor();

        // Subcenário (a): Corrida livre com CyclicBarrier(2)
        Produto prodA = salvarProduto("7891000300010", "Produto V8 SubA",
                new BigDecimal("10.00"), new BigDecimal("7.000"), new BigDecimal("1.000"));
        Venda vendaA = vendaRepository.save(new Venda());
        ComandoEstoqueVenda comandoA = new ComandoEstoqueVenda(
                vendaA.getId(), List.of(new ItemComando(prodA.getId(), new BigDecimal("3.000"))));

        CyclicBarrier barreiraA = new CyclicBarrier(2);
        AtomicInteger sucessosA = new AtomicInteger();
        Queue<Throwable> errosA = new ConcurrentLinkedQueue<>();

        try (ExecutorService poolA = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                poolA.submit(() -> {
                    try {
                        barreiraA.await(10, TimeUnit.SECONDS);
                        txTemplate.executeWithoutResult(status -> estoqueCommandService.devolverEstoque(comandoA));
                        sucessosA.incrementAndGet();
                    } catch (Throwable t) {
                        errosA.add(t);
                    }
                });
            }
            poolA.shutdown();
            assertThat(poolA.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        // Em corrida livre, ou 2 sucedem (1 inserção + 1 no-op via exists) ou 1 sucede e 1 falha com 23505
        assertThat(sucessosA.get()).isGreaterThanOrEqualTo(1);
        assertThat(produtoRepository.findById(prodA.getId()).orElseThrow().getEstoqueDisponivel())
                .isEqualByComparingTo("10.000");
        long devolucoesA = movimentacaoRepository.findAll().stream()
                .filter(m -> m.getTipo() == TipoMovimentacao.DEVOLUCAO && vendaA.getId().equals(m.getVendaId()))
                .count();
        assertThat(devolucoesA).isEqualTo(1L);

        // Subcenário (b): Sincronizadas por CountDownLatch para que ambas leiam exists == false antes do INSERT
        Produto prodB = salvarProduto("7891000300020", "Produto V8 SubB",
                new BigDecimal("10.00"), new BigDecimal("7.000"), new BigDecimal("1.000"));
        Venda vendaB = vendaRepository.save(new Venda());
        ComandoEstoqueVenda comandoB = new ComandoEstoqueVenda(
                vendaB.getId(), List.of(new ItemComando(prodB.getId(), new BigDecimal("3.000"))));

        CountDownLatch latchLeitura = new CountDownLatch(2);

        doAnswer(invocation -> {
            latchLeitura.countDown();
            boolean unblocked = latchLeitura.await(5, TimeUnit.SECONDS);
            if (!unblocked) {
                throw new TimeoutException("Latch leitura timed out");
            }
            return false;
        }).when(movimentacaoRepository).existsByVendaIdAndProdutoIdAndTipo(
                eq(vendaB.getId()), eq(prodB.getId()), eq(TipoMovimentacao.DEVOLUCAO));

        AtomicInteger sucessosB = new AtomicInteger();
        Queue<Throwable> errosB = new ConcurrentLinkedQueue<>();

        try (ExecutorService poolB = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                poolB.submit(() -> {
                    try {
                        txTemplate.executeWithoutResult(status -> estoqueCommandService.devolverEstoque(comandoB));
                        sucessosB.incrementAndGet();
                    } catch (Throwable t) {
                        errosB.add(t);
                    }
                });
            }
            poolB.shutdown();
            assertThat(poolB.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        // Exatamente 1 commit bem-sucedido e 1 DataIntegrityViolationException (SQLState 23505)
        assertThat(sucessosB.get()).withFailMessage(() -> "Falha: errosB=" + errosB).isEqualTo(1);
        assertThat(errosB).hasSize(1);
        Throwable erro = errosB.peek();
        assertThat(erro).isInstanceOf(DataIntegrityViolationException.class);
        Throwable root = NestedExceptionUtils.getRootCause(erro);
        assertThat(root).isInstanceOf(SQLException.class);
        assertThat(((SQLException) root).getSQLState()).isEqualTo("23505");

        // Saldo recomposto exatamente uma vez
        assertThat(produtoRepository.findById(prodB.getId()).orElseThrow().getEstoqueDisponivel())
                .isEqualByComparingTo("10.000");
        long devolucoesB = movimentacaoRepository.findAll().stream()
                .filter(m -> m.getTipo() == TipoMovimentacao.DEVOLUCAO && vendaB.getId().equals(m.getVendaId()))
                .count();
        assertThat(devolucoesB).isEqualTo(1L);

        long deadlocksServidorFim = obterDeadlocksServidor();
        assertThat(deadlocksServidorFim - deadlocksServidorInicio).isZero();

        Mockito.reset(movimentacaoRepository);
    }

    @Test
    @DisplayName("Teste 4 (Q4) — Carga mista de novas reservas, confirmações e devoluções mantém conservação sem deadlocks")
    void cargaMistaReservasConfirmacoesEDevolucoesMantemConservacaoDeEstoqueSemDeadlocks() throws Exception {
        Produto prodA = salvarProduto("7891000400010", "Produto Misto A",
                new BigDecimal("10.00"), new BigDecimal("100.000"), new BigDecimal("5.000"));
        Produto prodB = salvarProduto("7891000400020", "Produto Misto B",
                new BigDecimal("15.00"), new BigDecimal("100.000"), new BigDecimal("5.000"));

        List<Long> vendasConcluidas = new ArrayList<>();
        List<String> payloadsConfirmacao = new ArrayList<>();

        // 15 vendas concluídas (A: 15.000 baixado, B: 15.000 baixado)
        for (int i = 0; i < 15; i++) {
            Long vId = criarEConcluirVenda(List.of(
                    item(prodA.getId(), "1.000"),
                    item(prodB.getId(), "1.000")
            ));
            vendasConcluidas.add(vId);
        }

        // 15 vendas pendentes (AGUARDANDO_PAGAMENTO: A: 15.000 reservado, B: 15.000 reservado)
        for (int i = 0; i < 15; i++) {
            Venda vendaPendente = vendaService.iniciarVenda(new VendaRequest(List.of(
                    item(prodA.getId(), "1.000"),
                    item(prodB.getId(), "1.000")
            )));
            Long vId = vendaPendente.getId();
            BigDecimal valorTotal = vendaPendente.getValorTotal();
            Long pagId = pagamentoService.criarIntencaoPagamento(
                    vId, MetodoPagamento.DINHEIRO, valorTotal, UUID.randomUUID());
            pagamentoService.confirmarPagamentoWebhook(pagId, valorTotal, "NSU-PENDENTE-" + vId);
            payloadsConfirmacao.add("{\"vendaId\":" + vId + ",\"pagamentoId\":" + pagId + "}");
        }

        // Verificação do estado pré-carga: disponivel = 70.000, reservado = 15.000 (disponivel + reservado = 85.000)
        Produto aPre = produtoRepository.findById(prodA.getId()).orElseThrow();
        Produto bPre = produtoRepository.findById(prodB.getId()).orElseThrow();
        assertThat(aPre.getEstoqueDisponivel()).isEqualByComparingTo("70.000");
        assertThat(aPre.getEstoqueReservado()).isEqualByComparingTo("15.000");
        assertThat(bPre.getEstoqueDisponivel()).isEqualByComparingTo("70.000");
        assertThat(bPre.getEstoqueReservado()).isEqualByComparingTo("15.000");

        int totalThreads = 45;
        CyclicBarrier barreira = new CyclicBarrier(totalThreads);
        AtomicInteger sucessos = new AtomicInteger();
        Queue<Throwable> erros = new ConcurrentLinkedQueue<>();

        long deadlocksServidorInicio = obterDeadlocksServidor();

        try (ExecutorService pool = Executors.newFixedThreadPool(totalThreads)) {
            // 15 threads reservando com ordem invertida [B: 1.000, A: 1.000]
            for (int i = 0; i < 15; i++) {
                pool.submit(() -> {
                    try {
                        barreira.await(10, TimeUnit.SECONDS);
                        vendaService.iniciarVenda(new VendaRequest(List.of(
                                item(prodB.getId(), "1.000"),
                                item(prodA.getId(), "1.000")
                        )));
                        sucessos.incrementAndGet();
                    } catch (Throwable t) {
                        erros.add(t);
                    }
                });
            }

            // 15 threads confirmando baixa de pagamento
            for (String payload : payloadsConfirmacao) {
                pool.submit(() -> {
                    try {
                        barreira.await(10, TimeUnit.SECONDS);
                        vendaEventHandler.handlePagamentoConfirmado(payload);
                        sucessos.incrementAndGet();
                    } catch (Throwable t) {
                        erros.add(t);
                    }
                });
            }

            // 15 threads cancelando vendas concluídas
            for (Long vendaId : vendasConcluidas) {
                pool.submit(() -> {
                    try {
                        barreira.await(10, TimeUnit.SECONDS);
                        vendaService.cancelarVendaConcluida(vendaId);
                        sucessos.incrementAndGet();
                    } catch (Throwable t) {
                        erros.add(t);
                    }
                });
            }

            pool.shutdown();
            assertThat(pool.awaitTermination(45, TimeUnit.SECONDS)).isTrue();
        }

        long deadlocksServidorFim = obterDeadlocksServidor();
        assertThat(deadlocksServidorFim - deadlocksServidorInicio)
                .withFailMessage("O servidor PostgreSQL registrou deadlocks sob carga mista de 45 threads!")
                .isZero();

        assertThat(erros).isEmpty();
        assertThat(sucessos.get()).isEqualTo(45);

        // Conservação de saldo física:
        // disponivel = 70.000 - 15.000 (novas reservas) + 15.000 (devoluções) = 70.000
        // reservado = 15.000 + 15.000 (novas reservas) - 15.000 (baixas confirmadas) = 15.000
        // disponivel + reservado = 85.000
        Produto aFim = produtoRepository.findById(prodA.getId()).orElseThrow();
        Produto bFim = produtoRepository.findById(prodB.getId()).orElseThrow();

        assertThat(aFim.getEstoqueDisponivel()).isEqualByComparingTo("70.000");
        assertThat(aFim.getEstoqueReservado()).isEqualByComparingTo("15.000");
        assertThat(aFim.getEstoqueDisponivel().add(aFim.getEstoqueReservado())).isEqualByComparingTo("85.000");

        assertThat(bFim.getEstoqueDisponivel()).isEqualByComparingTo("70.000");
        assertThat(bFim.getEstoqueReservado()).isEqualByComparingTo("15.000");
        assertThat(bFim.getEstoqueDisponivel().add(bFim.getEstoqueReservado())).isEqualByComparingTo("85.000");
    }

    private Long criarEConcluirVenda(List<ItemVendaRequest> itens) {
        Venda venda = vendaService.iniciarVenda(new VendaRequest(itens));
        Long vendaId = venda.getId();
        BigDecimal valorTotal = venda.getValorTotal();

        UUID uuidPag = UUID.randomUUID();
        Long pagId = pagamentoService.criarIntencaoPagamento(
                vendaId, MetodoPagamento.DINHEIRO, valorTotal, uuidPag);
        pagamentoService.confirmarPagamentoWebhook(pagId, valorTotal, "NSU-IT-" + vendaId);

        vendaEventHandler.handlePagamentoConfirmado("{\"vendaId\":" + vendaId + ",\"pagamentoId\":" + pagId + "}");

        Venda concluida = vendaRepository.findById(vendaId).orElseThrow();
        assertThat(concluida.getStatus()).isEqualTo(StatusVenda.CONCLUIDA);
        return vendaId;
    }

    private Produto salvarProduto(String codigoBarras, String nome, BigDecimal preco,
                                  BigDecimal disponivel, BigDecimal minimo) {
        Produto p = new Produto();
        p.setNome(nome);
        p.setCodigoBarras(codigoBarras);
        p.setUnidadeMedida(UnidadeMedida.UN);
        p.setPrecoVenda(preco);
        p.setEstoqueDisponivel(disponivel);
        p.setEstoqueReservado(BigDecimal.ZERO);
        p.setEstoqueMinimo(minimo);
        return produtoRepository.saveAndFlush(p);
    }

    private long obterDeadlocksServidor() {
        try {
            jdbcTemplate.execute("SELECT pg_stat_force_next_flush()");
        } catch (Exception ignored) {
        }
        Long count = jdbcTemplate.queryForObject(
                "SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()", Long.class);
        return count != null ? count : 0L;
    }

    private static ItemVendaRequest item(Long produtoId, String quantidade) {
        return new ItemVendaRequest(produtoId, new BigDecimal(quantidade));
    }
}
