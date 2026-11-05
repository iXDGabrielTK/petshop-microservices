package inv.it;

import common.exception.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import inv.InvServiceApplication;
import inv.checkout.domain.model.Pagamento;
import inv.checkout.domain.model.StatusPagamento;
import inv.checkout.domain.model.StatusVenda;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.messaging.VendaEventHandler;
import inv.checkout.infrastructure.persistence.PagamentoRepository;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.checkout.infrastructure.web.dto.ItemVendaRequest;
import inv.checkout.infrastructure.web.dto.VendaRequest;
import inv.checkout.usecase.PagamentoService;
import inv.checkout.usecase.VendaService;
import inv.finance.domain.model.TipoLancamento;
import inv.finance.infrastructure.messaging.LancamentoRegistradoEvent;
import inv.finance.infrastructure.persistence.LancamentoFinanceiroRepository;
import inv.inventory.domain.model.MovimentacaoEstoque;
import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.TipoMovimentacao;
import inv.inventory.domain.model.UnidadeMedida;
import inv.inventory.infrastructure.persistence.MovimentacaoRepository;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import inv.shared.domain.MetodoPagamento;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PersistenceUnitUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Suíte de validação dos Gates de Concorrência e Isolamento do PR 4 (DEBT-ARCH-003 / E4 — Invariante 5):
 * - Gate 1: Rajada simultânea de webhooks e eventos de confirmação para o mesmo pagamento (absorção na Camada 2).
 * - Gate 2: Política de lock_timeout (SQLState 55P03) sob contenção de lock pessimista e contrato de exceções (retry/descarte sem requeue).
 * - Gate 4: Nível de isolamento READ COMMITTED e garantia de que pagamentos não é carregado na sessão JPA antes do lock.
 */
@RecordApplicationEvents
@Testcontainers
@SpringBootTest(classes = InvServiceApplication.class)
class PagamentoConcorrenciaGatesIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withCommand("postgres", "-c", "deadlock_timeout=100ms", "-c", "lock_timeout=500ms");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "30");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("features.cancelamento-venda-concluida.enabled", () -> "true");
        registry.add("finance.projection.diaria.enabled", () -> "true");
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
    private VendaRepository vendaRepository;

    @Autowired
    private PagamentoRepository pagamentoRepository;

    @Autowired
    private ProdutoRepository produtoRepository;

    @Autowired
    private MovimentacaoRepository movimentacaoRepository;

    @Autowired
    private LancamentoFinanceiroRepository lancamentoRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ApplicationEvents applicationEvents;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("ALTER SYSTEM SET lock_timeout = '10s'");
        jdbcTemplate.execute("SELECT pg_reload_conf()");
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
        applicationEvents.clear();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("ALTER SYSTEM SET lock_timeout = '10s'");
        jdbcTemplate.execute("SELECT pg_reload_conf()");
    }

    @Test
    @DisplayName("Gate 1 (Invariante 5.1) — Rajada simultânea de webhooks para o mesmo pagamento gera 1 contabilização, 1 lançamento e descarta demais com ACK")
    void gate1_rajadaSimultaneaWebhooksMesmoPagamentoDeveContabilizarUmaUnicaVezEDescartarComAck() throws Exception {
        validarRajadaWebhook(MetodoPagamento.PIX, false);
    }

    @ParameterizedTest
    @EnumSource(MetodoPagamento.class)
    void webhooksComParesDistintosDevemPreservarUmaUnicaConfirmacao(MetodoPagamento metodo) throws Exception {
        validarRajadaWebhook(metodo, true);
    }

    private void validarRajadaWebhook(MetodoPagamento metodo, boolean paresDistintos) throws Exception {
        Produto prod = salvarProduto("7893000100010", "Produto Gate 1 Burst",
                new BigDecimal("50.00"), new BigDecimal("20.000"), new BigDecimal("2.000"));

        Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(
                new ItemVendaRequest(prod.getId(), new BigDecimal("2.000"))
        )));
        Long vendaId = venda.getId();
        BigDecimal valorTotal = new BigDecimal("100.00");

        UUID uuidPagamento = UUID.randomUUID();
        Long pagamentoId = pagamentoService.criarIntencaoPagamento(
                vendaId, metodo, valorTotal, uuidPagamento);

        int totalThreads = 10;
        long deadlocksInicio = obterDeadlocksServidor();
        double metricExcedenteAntes = meterRegistry.counter(VendaEventHandler.METRICA_PAGAMENTO_EXCEDENTE).count();

        // 1. Rajada concorrente em confirmarPagamentoWebhook (Camada 1)
        CyclicBarrier barreiraWebhook = new CyclicBarrier(totalThreads);
        Queue<Throwable> errosWebhook = new ConcurrentLinkedQueue<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(totalThreads)) {
            for (int i = 0; i < totalThreads; i++) {
                BigDecimal valorChamada = paresDistintos ? valorTotal.add(BigDecimal.valueOf(i)) : valorTotal;
                String nsuChamada = "NSU-GATE1-" + (paresDistintos ? i : "BURST");
                pool.submit(() -> {
                    try {
                        barreiraWebhook.await(10, TimeUnit.SECONDS);
                        pagamentoService.confirmarPagamentoWebhook(pagamentoId, valorChamada, nsuChamada);
                    } catch (Throwable t) {
                        errosWebhook.add(t);
                    }
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(errosWebhook).isEmpty();

        Long outboxAposRajadaWebhook = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE event_type LIKE '%PagamentoConfirmadoEvent'",
                Long.class);
        assertThat(outboxAposRajadaWebhook).isEqualTo(1L);

        Pagamento vencedor = pagamentoRepository.findById(pagamentoId).orElseThrow();
        BigDecimal valorVencedor = vencedor.getValorRecebido();
        String nsuVencedor = vencedor.getNsuTransacao();
        assertThat(vencedor.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
        if (paresDistintos) {
            int indiceVencedor = Integer.parseInt(nsuVencedor.substring("NSU-GATE1-".length()));
            assertThat(indiceVencedor).isBetween(0, totalThreads - 1);
            assertThat(valorVencedor).isEqualByComparingTo(valorTotal.add(BigDecimal.valueOf(indiceVencedor)));
        } else {
            assertThat(valorVencedor).isEqualByComparingTo(valorTotal);
            assertThat(nsuVencedor).isEqualTo("NSU-GATE1-BURST");
        }

        // Chamada subsequente pós-commit na Camada 1 é no-op (confirmar() == false -> 0 novos eventos no Outbox)
        pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("999.00"), "NSU-REENTREGA");
        Long outboxAposDuplicataSequencial = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE event_type LIKE '%PagamentoConfirmadoEvent'",
                Long.class);
        assertThat(outboxAposDuplicataSequencial).isEqualTo(outboxAposRajadaWebhook);
        Pagamento aposReentrega = pagamentoRepository.findById(pagamentoId).orElseThrow();
        assertThat(aposReentrega.getValorRecebido()).isEqualByComparingTo(valorVencedor);
        assertThat(aposReentrega.getNsuTransacao()).isEqualTo(nsuVencedor);
        assertThat(aposReentrega.getDataHoraPagamento()).isEqualTo(vencedor.getDataHoraPagamento());

        // 2. Rajada concorrente em handlePagamentoConfirmado para o mesmo pagamentoId (Camada 2 + Camadas 3 a 5)
        String payload = "{\"vendaId\":" + vendaId + ",\"pagamentoId\":" + pagamentoId + "}";
        CyclicBarrier barreiraHandler = new CyclicBarrier(totalThreads);
        Queue<Throwable> errosHandler = new ConcurrentLinkedQueue<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(totalThreads)) {
            for (int i = 0; i < totalThreads; i++) {
                pool.submit(() -> {
                    try {
                        barreiraHandler.await(10, TimeUnit.SECONDS);
                        vendaEventHandler.handlePagamentoConfirmado(payload);
                    } catch (Throwable t) {
                        errosHandler.add(t);
                    }
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        long deadlocksFim = obterDeadlocksServidor();
        assertThat(deadlocksFim - deadlocksInicio)
                .withFailMessage("O servidor PostgreSQL registrou deadlocks durante a rajada de webhooks do mesmo pagamento!")
                .isZero();

        // Zero exceções propagadas (todas as N-1 chamadas excedentes absorvidas com ACK na Condição 1)
        assertThat(errosHandler).isEmpty();

        // Exatamente 1 contabilização na Venda
        Venda vendaFim = vendaRepository.findById(vendaId).orElseThrow();
        assertThat(vendaFim.getStatus()).isEqualTo(StatusVenda.CONCLUIDA);
        assertThat(vendaFim.getValorPago()).isEqualByComparingTo(valorVencedor);
        assertThat(vendaFim.getValorTroco()).isEqualByComparingTo(valorVencedor.subtract(valorTotal));

        // Pagamento contabilizado e NÃO marcado como excedente
        Pagamento pagFim = pagamentoRepository.findById(pagamentoId).orElseThrow();
        assertThat(pagFim.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
        assertThat(pagFim.isContabilizado()).isTrue();
        assertThat(pagFim.isExcedente()).isFalse();
        assertThat(pagFim.getValorRecebido()).isEqualByComparingTo(valorVencedor);
        assertThat(pagFim.getNsuTransacao()).isEqualTo(nsuVencedor);

        double metricExcedenteDepois = meterRegistry.counter(VendaEventHandler.METRICA_PAGAMENTO_EXCEDENTE).count();
        assertThat(metricExcedenteDepois - metricExcedenteAntes).isZero();

        // Estoque baixado exatamente 1 vez (18.000 disponível, 0.000 reservado, 1 movimentação SAIDA)
        Produto prodFim = produtoRepository.findById(prod.getId()).orElseThrow();
        assertThat(prodFim.getEstoqueDisponivel()).isEqualByComparingTo("18.000");
        assertThat(prodFim.getEstoqueReservado()).isEqualByComparingTo("0.000");

        List<MovimentacaoEstoque> saidas = movimentacaoRepository.findAll().stream()
                .filter(m -> m.getTipo() == TipoMovimentacao.SAIDA && vendaId.equals(m.getVendaId()))
                .toList();
        assertThat(saidas).hasSize(1);
        assertThat(saidas.getFirst().getQuantidade()).isEqualByComparingTo("2.000");

        // Exatamente 1 lançamento financeiro (CREDITO) e 1 LancamentoRegistradoEvent emitido
        Long countLancamentos = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lancamentos_financeiros WHERE venda_id = ? AND referencia = ? AND tipo = 'CREDITO'",
                Long.class, vendaId, uuidPagamento);
        assertThat(countLancamentos).isEqualTo(1L);

        List<LancamentoRegistradoEvent> eventosLancamento = applicationEvents.stream(LancamentoRegistradoEvent.class)
                .filter(e -> vendaId.equals(e.vendaId()) && e.tipo() == TipoLancamento.CREDITO)
                .toList();
        assertThat(eventosLancamento).hasSize(1);
        assertThat(eventosLancamento.getFirst().valorAssinado()).isEqualByComparingTo("100.00");

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            BigDecimal saldo = jdbcTemplate.queryForObject(
                    "SELECT saldo FROM financial_projection_venda WHERE venda_id = ?",
                    BigDecimal.class, vendaId);
            assertThat(saldo).isNotNull().isEqualByComparingTo("100.00");
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void confirmacaoEFalhaConcorrentesDevemRespeitarPrimeiraTransicao(boolean confirmacaoPrimeiro) throws Exception {
        Produto produto = salvarProduto("7893000500010", "Corrida confirmação e falha",
                new BigDecimal("100.00"), new BigDecimal("10.000"), BigDecimal.ONE);
        Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(new ItemVendaRequest(produto.getId(), BigDecimal.ONE))));
        Long pagamentoId = pagamentoService.criarIntencaoPagamento(venda.getId(), MetodoPagamento.CARTAO_CREDITO,
                new BigDecimal("100.00"), UUID.randomUUID());
        Runnable confirmar = () -> pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("110.00"), "NSU-VENCEDOR");
        Runnable falhar = () -> pagamentoService.falharPagamentoWebhook(pagamentoId);

        executarComPagamentoRetido(confirmacaoPrimeiro ? confirmar : falhar,
                () -> assertThatThrownBy(() -> (confirmacaoPrimeiro ? falhar : confirmar).run())
                        .isInstanceOf(BusinessException.class));

        Pagamento pagamento = pagamentoRepository.findById(pagamentoId).orElseThrow();
        assertThat(pagamento.getStatus()).isEqualTo(confirmacaoPrimeiro ? StatusPagamento.CONFIRMADO : StatusPagamento.FALHOU);
        assertThat(pagamento.getValorRecebido()).isEqualByComparingTo(confirmacaoPrimeiro ? "110.00" : "0.00");
        assertThat(pagamento.getNsuTransacao()).isEqualTo(confirmacaoPrimeiro ? "NSU-VENCEDOR" : null);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox WHERE event_type LIKE '%PagamentoConfirmadoEvent'", Long.class))
                .isEqualTo(confirmacaoPrimeiro ? 1L : 0L);
    }

    @Test
    void falhaNaOutboxDeveReverterConfirmacaoELiberarNovaTentativa() {
        Produto produto = salvarProduto("7893000600010", "Rollback da confirmação",
                new BigDecimal("100.00"), new BigDecimal("10.000"), BigDecimal.ONE);
        Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(new ItemVendaRequest(produto.getId(), BigDecimal.ONE))));
        Long pagamentoId = pagamentoService.criarIntencaoPagamento(venda.getId(), MetodoPagamento.PIX,
                new BigDecimal("100.00"), UUID.randomUUID());
        jdbcTemplate.execute("ALTER TABLE outbox ADD CONSTRAINT teste_rejeitar_confirmacao CHECK (event_type NOT LIKE '%PagamentoConfirmadoEvent')");
        try {
            assertThatThrownBy(() -> pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("110.00"), "NSU-ROLLBACK"))
                    .isInstanceOf(RuntimeException.class);
            Pagamento pagamento = pagamentoRepository.findById(pagamentoId).orElseThrow();
            assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.PENDENTE);
            assertThat(pagamento.getValorRecebido()).isEqualByComparingTo("0.00");
            assertThat(pagamento.getNsuTransacao()).isNull();
            assertThat(pagamento.getDataHoraPagamento()).isNull();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox WHERE event_type LIKE '%PagamentoConfirmadoEvent'", Long.class)).isZero();
        } finally {
            jdbcTemplate.execute("ALTER TABLE outbox DROP CONSTRAINT teste_rejeitar_confirmacao");
        }
        pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("120.00"), "NSU-RETRY");
        Pagamento pagamento = pagamentoRepository.findById(pagamentoId).orElseThrow();
        assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.CONFIRMADO);
        assertThat(pagamento.getValorRecebido()).isEqualByComparingTo("120.00");
        assertThat(pagamento.getNsuTransacao()).isEqualTo("NSU-RETRY");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox WHERE event_type LIKE '%PagamentoConfirmadoEvent'", Long.class)).isEqualTo(1L);
    }

    @Test
    void reentregaWebhookConcorrenteComCancelamentoNaoDeveRestaurarPagamentoEstornado() throws Exception {
        Produto produto = salvarProduto("7893000700010", "Webhook e cancelamento",
                new BigDecimal("100.00"), new BigDecimal("10.000"), BigDecimal.ONE);
        Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(new ItemVendaRequest(produto.getId(), BigDecimal.ONE))));
        Long pagamentoId = pagamentoService.criarIntencaoPagamento(venda.getId(), MetodoPagamento.PIX,
                new BigDecimal("100.00"), UUID.randomUUID());
        pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("100.00"), "NSU-ORIGINAL");
        String payload = "{\"vendaId\":" + venda.getId() + ",\"pagamentoId\":" + pagamentoId + "}";
        vendaEventHandler.handlePagamentoConfirmado(payload);

        executarComPagamentoRetido(
                () -> pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("999.00"), "NSU-IGNORADO"),
                () -> vendaService.cancelarVendaConcluida(venda.getId()));

        assertThatThrownBy(() -> pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("999.00"), "NSU-IGNORADO"))
                .isInstanceOf(BusinessException.class);
        vendaEventHandler.handlePagamentoConfirmado(payload);
        Pagamento pagamento = pagamentoRepository.findById(pagamentoId).orElseThrow();
        assertThat(pagamento.getStatus()).isEqualTo(StatusPagamento.ESTORNADO);
        assertThat(pagamento.isContabilizado()).isTrue();
        assertThat(pagamento.getValorRecebido()).isEqualByComparingTo("100.00");
        assertThat(pagamento.getNsuTransacao()).isEqualTo("NSU-ORIGINAL");
        Venda cancelada = vendaRepository.findById(venda.getId()).orElseThrow();
        assertThat(cancelada.getStatus()).isEqualTo(StatusVenda.CANCELADA);
        assertThat(cancelada.getValorPago()).isEqualByComparingTo("100.00");
        assertThat(cancelada.getValorTroco()).isEqualByComparingTo("0.00");
        Produto devolvido = produtoRepository.findById(produto.getId()).orElseThrow();
        assertThat(devolvido.getEstoqueDisponivel()).isEqualByComparingTo("10.000");
        assertThat(devolvido.getEstoqueReservado()).isEqualByComparingTo("0.000");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM movimentacoes_estoque WHERE venda_id = ? AND tipo = 'DEVOLUCAO'", Long.class, venda.getId())).isEqualTo(1L);
        assertThat(lancamentoRepository.count()).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox WHERE event_type LIKE '%PagamentoConfirmadoEvent'", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox WHERE event_type LIKE '%VendaEstornadaEvent'", Long.class)).isEqualTo(1L);
    }

    private void executarComPagamentoRetido(Runnable primeira, Runnable concorrente) throws Exception {
        long deadlocksInicio = obterDeadlocksServidor();
        CountDownLatch lockAdquirido = new CountDownLatch(1);
        Queue<Throwable> erros = new ConcurrentLinkedQueue<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            pool.submit(() -> {
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        primeira.run();
                        lockAdquirido.countDown();
                        await().atMost(5, TimeUnit.SECONDS).until(() -> jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM pg_locks WHERE NOT granted", Integer.class) > 0);
                    });
                } catch (Throwable t) {
                    erros.add(t);
                } finally {
                    lockAdquirido.countDown();
                }
            });
            pool.submit(() -> {
                try {
                    assertThat(lockAdquirido.await(5, TimeUnit.SECONDS)).isTrue();
                    concorrente.run();
                } catch (Throwable t) {
                    erros.add(t);
                }
            });
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(erros).isEmpty();
        assertThat(obterDeadlocksServidor() - deadlocksInicio).isZero();
    }

    @Test
    @DisplayName("Gate 2 (Invariante 5.2) — Contenção em lock pessimista estoura lock_timeout (55P03) com rollback íntegro e contrato de exceções para retry e descarte sem requeue")
    void gate2_contencaoLockPessimistaEstouraLockTimeoutComRollbackEContratoExcecoes() throws Exception {
        try {
            // Restaura o lock_timeout=500ms parametrizado na inicialização do container PostgreSQL
            jdbcTemplate.execute("ALTER SYSTEM RESET lock_timeout");
            jdbcTemplate.execute("SELECT pg_reload_conf()");

            String lockTimeoutConfig = jdbcTemplate.queryForObject("SHOW lock_timeout", String.class);
            assertThat(lockTimeoutConfig).isEqualTo("500ms");

            Produto prod = salvarProduto("7893000200010", "Produto Gate 2 LockTimeout",
                    new BigDecimal("100.00"), new BigDecimal("10.000"), new BigDecimal("1.000"));

            Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(
                    new ItemVendaRequest(prod.getId(), new BigDecimal("1.000"))
            )));
            Long vendaId = venda.getId();
            UUID uuidPag = UUID.randomUUID();
            Long pagamentoId = pagamentoService.criarIntencaoPagamento(
                    vendaId, MetodoPagamento.PIX, new BigDecimal("100.00"), uuidPag);
            pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("100.00"), "NSU-GATE2-TIMEOUT");

            String payload = "{\"vendaId\":" + vendaId + ",\"pagamentoId\":" + pagamentoId + "}";
            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

            CountDownLatch latchLockAdquirido = new CountDownLatch(1);
            CountDownLatch latchLiberarLock = new CountDownLatch(1);
            AtomicReference<Throwable> erroSeguradorLock = new AtomicReference<>();
            AtomicReference<Throwable> erroTentativaComTimeout = new AtomicReference<>();

            long deadlocksInicio = obterDeadlocksServidor();

            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                // Thread 1: Adquire e retém o lock pessimista (FOR UPDATE) na linha da venda
                pool.submit(() -> {
                    try {
                        txTemplate.executeWithoutResult(status -> {
                            vendaRepository.findByIdWithLock(vendaId).orElseThrow();
                            latchLockAdquirido.countDown();
                            try {
                                assertThat(latchLiberarLock.await(10, TimeUnit.SECONDS)).isTrue();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(e);
                            }
                        });
                    } catch (Throwable t) {
                        erroSeguradorLock.set(t);
                    }
                });

                // Thread 2: Tenta processar confirmação enquanto o lock está retido -> estoura lock_timeout (55P03)
                pool.submit(() -> {
                    try {
                        assertThat(latchLockAdquirido.await(5, TimeUnit.SECONDS)).isTrue();
                        vendaEventHandler.handlePagamentoConfirmado(payload);
                    } catch (Throwable t) {
                        erroTentativaComTimeout.set(t);
                    } finally {
                        latchLiberarLock.countDown();
                    }
                });

                pool.shutdown();
                assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(erroSeguradorLock.get()).isNull();

            // 1. Prova que a segunda tentativa estourou lock_timeout (SQLState 55P03 -> PessimisticLockingFailureException)
            Throwable exTimeout = erroTentativaComTimeout.get();
            assertThat(exTimeout)
                    .isNotNull()
                    .isInstanceOf(RuntimeException.class)
                    .hasCauseInstanceOf(PessimisticLockingFailureException.class);
            Throwable rootCause = NestedExceptionUtils.getRootCause(exTimeout);
            assertThat(rootCause).isInstanceOf(SQLException.class);
            assertThat(((SQLException) rootCause).getSQLState()).isEqualTo("55P03");

            // 2. Rollback integral da tentativa que sofreu lock_timeout: venda segue AGUARDANDO_PAGAMENTO, pagamento não contabilizado
            Venda vendaPosTimeout = vendaRepository.findById(vendaId).orElseThrow();
            assertThat(vendaPosTimeout.getStatus()).isEqualTo(StatusVenda.AGUARDANDO_PAGAMENTO);
            Pagamento pagPosTimeout = pagamentoRepository.findById(pagamentoId).orElseThrow();
            assertThat(pagPosTimeout.isContabilizado()).isFalse();
            assertThat(lancamentoRepository.count()).isZero();

            // 3. Após liberação do lock, retry/reentrega processa normalmente e reentrega subsequente é descartada com ACK
            vendaEventHandler.handlePagamentoConfirmado(payload);
            assertThat(vendaRepository.findById(vendaId).orElseThrow().getStatus()).isEqualTo(StatusVenda.CONCLUIDA);
            vendaEventHandler.handlePagamentoConfirmado(payload); // reentrega absorvida na Condição 1 (ACK)
            assertThat(lancamentoRepository.count()).isEqualTo(1L);

            // 4. Falha não-retentável (violação de integridade/negócio ou JSON inválido) lança AmqpRejectAndDontRequeueException -> contrato para descarte sem requeue
            assertThatThrownBy(() -> vendaEventHandler.handlePagamentoConfirmado("{\"vendaId\":999999,\"pagamentoId\":" + pagamentoId + "}"))
                    .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                    .hasCauseInstanceOf(BusinessException.class);

            long deadlocksFim = obterDeadlocksServidor();
            assertThat(deadlocksFim - deadlocksInicio).isZero();
        } finally {
            jdbcTemplate.execute("ALTER SYSTEM SET lock_timeout = '10s'");
            jdbcTemplate.execute("SELECT pg_reload_conf()");
        }
    }

    @Test
    @DisplayName("Gate 4 (Invariante 5.4) — Isolamento READ COMMITTED efetivo e coleção pagamentos não carregada na sessão JPA antes do lock")
    void gate4_isolamentoReadCommittedEPagamentosNaoCarregadoAntesDoLockGarantemVisibilidadePosLock() throws Exception {
        // 1. Mapeamento estático: Venda.pagamentos deve ser LAZY para não hidratar antes/durante o SELECT ... FOR UPDATE
        OneToMany mappingPagamentos = Venda.class.getDeclaredField("pagamentos").getAnnotation(OneToMany.class);
        assertThat(mappingPagamentos).isNotNull();
        assertThat(mappingPagamentos.fetch()).isEqualTo(FetchType.LAZY);

        Produto prod = salvarProduto("7893000400010", "Produto Gate 4 Isolamento",
                new BigDecimal("75.00"), new BigDecimal("10.000"), new BigDecimal("1.000"));

        Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(
                new ItemVendaRequest(prod.getId(), new BigDecimal("1.000"))
        )));
        Long vendaId = venda.getId();
        UUID uuidPag = UUID.randomUUID();
        Long pagamentoId = pagamentoService.criarIntencaoPagamento(
                vendaId, MetodoPagamento.PIX, new BigDecimal("75.00"), uuidPag);
        pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("75.00"), "NSU-GATE4-ISO");

        String payload = "{\"vendaId\":" + vendaId + ",\"pagamentoId\":" + pagamentoId + "}";
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        PersistenceUnitUtil persistenceUnitUtil = entityManagerFactory.getPersistenceUnitUtil();
        double metricExcedenteAntes = meterRegistry.counter(VendaEventHandler.METRICA_PAGAMENTO_EXCEDENTE).count();

        // 2. Prova determinística com transações concorrentes:
        // Thread 2 (sessão JPA explícita) e Thread 3 (VendaEventHandler.handlePagamentoConfirmado) aguardam em findByIdWithLock
        // enquanto Thread 1 contabiliza e commita. A sincronização usa pg_locks (SELECT COUNT(*) WHERE NOT granted) >= 2, sem Thread.sleep.
        CountDownLatch latchT1DetemLock = new CountDownLatch(1);
        CountDownLatch latchWaitersProntos = new CountDownLatch(2);
        AtomicReference<String> isolamentoT2 = new AtomicReference<>();
        AtomicBoolean pagamentosCarregadoAposLockT1 = new AtomicBoolean(true);
        AtomicBoolean pagamentosCarregadoAposLockT2 = new AtomicBoolean(true);
        AtomicBoolean pagamentoPreCarregadoNaSessaoT2 = new AtomicBoolean(true);
        AtomicBoolean contabilizadoLidoPorT2PosLock = new AtomicBoolean(false);
        Queue<Throwable> erros = new ConcurrentLinkedQueue<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
            // Thread 1: Adquire o lock primeiro via findByIdWithLock, aguarda T2 e T3 bloquearem fisicamente em pg_locks,
            // depois contabiliza o pagamento e commita.
            pool.submit(() -> {
                try {
                    txTemplate.executeWithoutResult(status -> {
                        Venda v1 = vendaRepository.findByIdWithLock(vendaId).orElseThrow();
                        pagamentosCarregadoAposLockT1.set(persistenceUnitUtil.isLoaded(v1, "pagamentos"));
                        latchT1DetemLock.countDown();

                        try {
                            assertThat(latchWaitersProntos.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }

                        // Confirma em pg_locks que ambas as transações concorrentes (T2 e T3) estão fisicamente bloqueadas no lock
                        await().atMost(5, TimeUnit.SECONDS)
                                .pollInterval(10, TimeUnit.MILLISECONDS)
                                .until(() -> {
                                    Integer bloqueadas = jdbcTemplate.queryForObject(
                                            "SELECT COUNT(*) FROM pg_locks WHERE NOT granted",
                                            Integer.class);
                                    return bloqueadas != null && bloqueadas >= 2;
                                });

                        Pagamento p1 = pagamentoRepository.findById(pagamentoId).orElseThrow();
                        v1.registrarPagamentoConfirmado(p1);
                        v1.concluir();
                        vendaRepository.save(v1);
                    });
                } catch (Throwable t) {
                    erros.add(t);
                }
            });

            // Thread 2: Inicia sua transação ANTES do commit de T1, comprova isolamento READ COMMITTED e ausência de
            // Pagamento na sessão JPA antes do lock, bloqueia em findByIdWithLock e, após desbloquear, lê contabilizado == true.
            pool.submit(() -> {
                try {
                    txTemplate.executeWithoutResult(status -> {
                        try {
                            assertThat(latchT1DetemLock.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }

                        String iso = jdbcTemplate.queryForObject("SHOW transaction_isolation", String.class);
                        isolamentoT2.set(iso);

                        // Antes de adquirir o lock, nenhuma instância de Pagamento foi carregada no Persistence Context
                        pagamentoPreCarregadoNaSessaoT2.set(
                                entityManager.unwrap(org.hibernate.Session.class)
                                        .getStatistics()
                                        .getEntityCount() > 0
                        );

                        latchWaitersProntos.countDown();

                        // Bloqueia no SELECT ... FOR UPDATE até o commit de T1
                        Venda v2 = vendaRepository.findByIdWithLock(vendaId).orElseThrow();

                        // Mesmo imediatamente após adquirir o lock em Venda, a coleção pagamentos NÃO foi carregada
                        pagamentosCarregadoAposLockT2.set(persistenceUnitUtil.isLoaded(v2, "pagamentos"));

                        // Ao consultar Pagamento pós-lock sob READ COMMITTED, enxerga contabilizado == true comitado por T1
                        Pagamento p2 = pagamentoRepository.findById(pagamentoId).orElseThrow();
                        contabilizadoLidoPorT2PosLock.set(p2.isContabilizado());
                    });
                } catch (Throwable t) {
                    erros.add(t);
                }
            });

            // Thread 3: Invoca VendaEventHandler.handlePagamentoConfirmado enquanto T1 detém o lock;
            // como Pagamento só é carregado APÓS findByIdWithLock sob READ COMMITTED, enxerga contabilizado == true e sai na Condição 1 (ACK).
            pool.submit(() -> {
                try {
                    assertThat(latchT1DetemLock.await(5, TimeUnit.SECONDS)).isTrue();
                    latchWaitersProntos.countDown();
                    vendaEventHandler.handlePagamentoConfirmado(payload);
                } catch (Throwable t) {
                    erros.add(t);
                }
            });

            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(erros).isEmpty();
        assertThat(isolamentoT2.get()).isEqualToIgnoringCase("read committed");
        assertThat(pagamentoPreCarregadoNaSessaoT2.get()).isFalse();
        assertThat(pagamentosCarregadoAposLockT1.get()).isFalse();
        assertThat(pagamentosCarregadoAposLockT2.get()).isFalse();
        assertThat(contabilizadoLidoPorT2PosLock.get()).isTrue();

        Pagamento pagFim = pagamentoRepository.findById(pagamentoId).orElseThrow();
        assertThat(pagFim.isContabilizado()).isTrue();
        assertThat(pagFim.isExcedente()).isFalse();
        double metricExcedenteDepois = meterRegistry.counter(VendaEventHandler.METRICA_PAGAMENTO_EXCEDENTE).count();
        assertThat(metricExcedenteDepois - metricExcedenteAntes).isZero();
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
}
