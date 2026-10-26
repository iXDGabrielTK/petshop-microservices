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
import org.springframework.core.NestedExceptionUtils;
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
import java.sql.SQLException;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validação de concorrência na mesma venda: corrida entre confirmação de pagamento (VendaEventHandler)
 * e expiração de venda (VendaService.cancelarVendaExpirada).
 * Executa 30 repetições na CyclicBarrier: sempre exatamente 1 vencedor (CONCLUIDA ou CANCELADA),
 * zero deadlocks (40P01) e saldos físicos rigorosamente coerentes.
 */
@Testcontainers
@SpringBootTest(classes = InvServiceApplication.class)
class HandlerExpiracaoMesmaVendaIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "30");
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
    @DisplayName("E4 — Pagamento e expiração da mesma venda multi-item disparados simultaneamente (CyclicBarrier, 30 repetições): sempre 1 vencedor, 0 deadlocks e saldos coerentes")
    void corridaPagamentoVsExpiracaoMesmaVendaDeveTerUnicoVencedorESaldosCoerentes() throws Exception {
        int repeticoes = 30;
        AtomicInteger concluidasCount = new AtomicInteger();
        AtomicInteger canceladasCount = new AtomicInteger();
        Queue<Throwable> deadlocks = new ConcurrentLinkedQueue<>();
        Queue<Throwable> errosInesperados = new ConcurrentLinkedQueue<>();

        long deadlocksServidorInicio = obterDeadlocksServidor();

        for (int i = 0; i < repeticoes; i++) {
            // 1. Cria 2 produtos com 10 unidades cada para validar multi-item na mesma venda
            Produto prodA = new Produto();
            prodA.setNome("Produto A Corrida Mesma Venda #" + i);
            prodA.setCodigoBarras("7896666" + String.format("%05dA", i));
            prodA.setUnidadeMedida(UnidadeMedida.UN);
            prodA.setPrecoVenda(new BigDecimal("50.00"));
            prodA.setEstoqueDisponivel(new BigDecimal("10.000"));
            prodA.setEstoqueReservado(new BigDecimal("0.000"));
            prodA.setEstoqueMinimo(new BigDecimal("2.000"));
            prodA = produtoRepository.saveAndFlush(prodA);

            Produto prodB = new Produto();
            prodB.setNome("Produto B Corrida Mesma Venda #" + i);
            prodB.setCodigoBarras("7896666" + String.format("%05dB", i));
            prodB.setUnidadeMedida(UnidadeMedida.UN);
            prodB.setPrecoVenda(new BigDecimal("50.00"));
            prodB.setEstoqueDisponivel(new BigDecimal("10.000"));
            prodB.setEstoqueReservado(new BigDecimal("0.000"));
            prodB.setEstoqueMinimo(new BigDecimal("2.000"));
            prodB = produtoRepository.saveAndFlush(prodB);

            // 2. Inicia venda reservando 2 unidades de A (disp=8, res=2) e 3 de B (disp=7, res=3)
            Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(
                    new ItemVendaRequest(prodA.getId(), new BigDecimal("2.000")),
                    new ItemVendaRequest(prodB.getId(), new BigDecimal("3.000"))
            )));

            Long pagamentoId = pagamentoService.criarIntencaoPagamento(
                    venda.getId(), MetodoPagamento.PIX, new BigDecimal("250.00"), UUID.randomUUID());
            pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("250.00"), "NSU-RACE-" + i);

            final Long vendaId = venda.getId();
            final String payload = """
                    {"vendaId":%d,"pagamentoId":%d}
                    """.formatted(vendaId, pagamentoId);

            CyclicBarrier barreira = new CyclicBarrier(2);

            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                // Thread 1: Confirmação de pagamento (baixa definitiva em lote)
                pool.submit(() -> {
                    try {
                        barreira.await(5, TimeUnit.SECONDS);
                        vendaEventHandler.handlePagamentoConfirmado(payload);
                    } catch (Throwable t) {
                        if (isDeadlock(t)) {
                            deadlocks.add(t);
                        } else if (!isErroEsperadoPerdedor(t)) {
                            errosInesperados.add(t);
                        }
                    }
                });

                // Thread 2: Cancelamento por expiração (estorno em lote)
                pool.submit(() -> {
                    try {
                        barreira.await(5, TimeUnit.SECONDS);
                        vendaService.cancelarVendaExpirada(vendaId);
                    } catch (Throwable t) {
                        if (isDeadlock(t)) {
                            deadlocks.add(t);
                        } else if (!isErroEsperadoPerdedor(t)) {
                            errosInesperados.add(t);
                        }
                    }
                });

                pool.shutdown();
                assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }

            // 3. Validação do resultado desta repetição
            assertThat(deadlocks).isEmpty();
            assertThat(errosInesperados).isEmpty();

            Venda vendaFinal = vendaRepository.findById(vendaId).orElseThrow();
            StatusVenda statusFinal = vendaFinal.getStatus();
            assertThat(statusFinal).isIn(StatusVenda.CONCLUIDA, StatusVenda.CANCELADA);

            Produto prodAAtualizado = produtoRepository.findById(prodA.getId()).orElseThrow();
            Produto prodBAtualizado = produtoRepository.findById(prodB.getId()).orElseThrow();
            List<MovimentacaoEstoque> movsVenda = movimentacaoRepository.findAll().stream()
                    .filter(m -> vendaId.equals(m.getVendaId()))
                    .toList();

            if (statusFinal == StatusVenda.CONCLUIDA) {
                concluidasCount.incrementAndGet();
                // Baixa realizada: reservado zerado em ambos, disponivel permanece em 8 e 7
                assertThat(prodAAtualizado.getEstoqueReservado()).isEqualByComparingTo("0.000");
                assertThat(prodAAtualizado.getEstoqueDisponivel()).isEqualByComparingTo("8.000");
                assertThat(prodBAtualizado.getEstoqueReservado()).isEqualByComparingTo("0.000");
                assertThat(prodBAtualizado.getEstoqueDisponivel()).isEqualByComparingTo("7.000");

                // Movimentações: 2 RESERVAS e 2 SAIDAS
                assertThat(movsVenda).extracting(MovimentacaoEstoque::getTipo)
                        .containsExactlyInAnyOrder(
                                TipoMovimentacao.RESERVA, TipoMovimentacao.RESERVA,
                                TipoMovimentacao.SAIDA, TipoMovimentacao.SAIDA);
            } else {
                canceladasCount.incrementAndGet();
                // Estorno realizado: reservado zerado em ambos, disponivel restaurado para 10
                assertThat(prodAAtualizado.getEstoqueReservado()).isEqualByComparingTo("0.000");
                assertThat(prodAAtualizado.getEstoqueDisponivel()).isEqualByComparingTo("10.000");
                assertThat(prodBAtualizado.getEstoqueReservado()).isEqualByComparingTo("0.000");
                assertThat(prodBAtualizado.getEstoqueDisponivel()).isEqualByComparingTo("10.000");

                // Movimentações: 2 RESERVAS e 2 CANCELAMENTOS
                assertThat(movsVenda).extracting(MovimentacaoEstoque::getTipo)
                        .containsExactlyInAnyOrder(
                                TipoMovimentacao.RESERVA, TipoMovimentacao.RESERVA,
                                TipoMovimentacao.CANCELAMENTO_RESERVA, TipoMovimentacao.CANCELAMENTO_RESERVA);
            }
        }

        long deadlocksServidorFim = obterDeadlocksServidor();
        assertThat(deadlocksServidorFim - deadlocksServidorInicio)
                .withFailMessage("PostgreSQL registrou deadlocks durante corrida de confirmação vs expiração na mesma venda!")
                .isZero();

        // 4. Asserções finais globais
        assertThat(deadlocks).isEmpty();
        assertThat(errosInesperados).isEmpty();
        assertThat(concluidasCount.get() + canceladasCount.get()).isEqualTo(repeticoes);
    }

    @Test
    @DisplayName("A4/Q3 — Corrida cruzada entre confirmação de baixa [A,B] e estorno de reserva [B,A] (15 repetições): ordenação por produtoId impede 40P01")
    void corridaCruzadaBaixaVsEstornoCarrinhosInvertidos() throws Exception {
        int repeticoes = 15;
        AtomicInteger concluidasCount = new AtomicInteger();
        AtomicInteger canceladasCount = new AtomicInteger();
        Queue<Throwable> deadlocks = new ConcurrentLinkedQueue<>();
        Queue<Throwable> errosInesperados = new ConcurrentLinkedQueue<>();

        Produto prodA = new Produto();
        prodA.setNome("Produto Multi Baixa-Estorno A");
        prodA.setCodigoBarras("7895555000010");
        prodA.setUnidadeMedida(UnidadeMedida.UN);
        prodA.setPrecoVenda(new BigDecimal("50.00"));
        prodA.setEstoqueDisponivel(new BigDecimal("100.000"));
        prodA.setEstoqueReservado(new BigDecimal("0.000"));
        prodA.setEstoqueMinimo(new BigDecimal("2.000"));
        prodA = produtoRepository.saveAndFlush(prodA);

        Produto prodB = new Produto();
        prodB.setNome("Produto Multi Baixa-Estorno B");
        prodB.setCodigoBarras("7895555000020");
        prodB.setUnidadeMedida(UnidadeMedida.UN);
        prodB.setPrecoVenda(new BigDecimal("50.00"));
        prodB.setEstoqueDisponivel(new BigDecimal("100.000"));
        prodB.setEstoqueReservado(new BigDecimal("0.000"));
        prodB.setEstoqueMinimo(new BigDecimal("2.000"));
        prodB = produtoRepository.saveAndFlush(prodB);

        Long idA = prodA.getId();
        Long idB = prodB.getId();
        assertThat(idA).isLessThan(idB);

        long deadlocksServidorInicio = obterDeadlocksServidor();

        for (int i = 0; i < repeticoes; i++) {
            // Venda 1: itens [A, B]
            Venda venda1 = vendaService.iniciarVenda(new VendaRequest(List.of(
                    new ItemVendaRequest(idA, new BigDecimal("2.000")),
                    new ItemVendaRequest(idB, new BigDecimal("2.000"))
            )));
            Long pagId1 = pagamentoService.criarIntencaoPagamento(
                    venda1.getId(), MetodoPagamento.PIX, new BigDecimal("200.00"), UUID.randomUUID());
            pagamentoService.confirmarPagamentoWebhook(pagId1, new BigDecimal("200.00"), "NSU-BAIXA-ESTORNO-" + i);

            final Long venda1Id = venda1.getId();
            final String payloadVenda1 = """
                    {"vendaId":%d,"pagamentoId":%d}
                    """.formatted(venda1Id, pagId1);

            // Venda 2: itens [B, A] (ordem inversa de itens!)
            Venda venda2 = vendaService.iniciarVenda(new VendaRequest(List.of(
                    new ItemVendaRequest(idB, new BigDecimal("2.000")),
                    new ItemVendaRequest(idA, new BigDecimal("2.000"))
            )));
            final Long venda2Id = venda2.getId();

            CyclicBarrier barreira = new CyclicBarrier(2);

            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                // Thread 1: Confirmação de pagamento da Venda 1 (confirmarBaixa em [A, B])
                pool.submit(() -> {
                    try {
                        barreira.await(5, TimeUnit.SECONDS);
                        vendaEventHandler.handlePagamentoConfirmado(payloadVenda1);
                        concluidasCount.incrementAndGet();
                    } catch (Throwable t) {
                        if (isDeadlock(t)) {
                            deadlocks.add(t);
                        } else {
                            errosInesperados.add(t);
                        }
                    }
                });

                // Thread 2: Cancelamento por expiração da Venda 2 (estornarReserva em [B, A])
                pool.submit(() -> {
                    try {
                        barreira.await(5, TimeUnit.SECONDS);
                        vendaService.cancelarVendaExpirada(venda2Id);
                        canceladasCount.incrementAndGet();
                    } catch (Throwable t) {
                        if (isDeadlock(t)) {
                            deadlocks.add(t);
                        } else {
                            errosInesperados.add(t);
                        }
                    }
                });

                pool.shutdown();
                assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(deadlocks).isEmpty();
            assertThat(errosInesperados).isEmpty();

            Venda v1Final = vendaRepository.findById(venda1Id).orElseThrow();
            assertThat(v1Final.getStatus()).isEqualTo(StatusVenda.CONCLUIDA);

            Venda v2Final = vendaRepository.findById(venda2Id).orElseThrow();
            assertThat(v2Final.getStatus()).isEqualTo(StatusVenda.CANCELADA);
        }

        long deadlocksServidorFim = obterDeadlocksServidor();
        assertThat(deadlocksServidorFim - deadlocksServidorInicio)
                .withFailMessage("PostgreSQL registrou deadlocks durante corrida cruzada entre baixa [A,B] e estorno [B,A]!")
                .isZero();

        assertThat(deadlocks).isEmpty();
        assertThat(errosInesperados).isEmpty();
        assertThat(concluidasCount.get()).isEqualTo(repeticoes);
        assertThat(canceladasCount.get()).isEqualTo(repeticoes);

        // Cada repetição baixou 2 de A e 2 de B na Venda 1, e estornou os 2 reservados na Venda 2.
        // Disponível consumido líquido = 15 * 2 = 30 unidades de cada produto.
        // Disponível final: 100 - 30 = 70.000; Reservado final: 0.000.
        Produto prodAFinal = produtoRepository.findById(idA).orElseThrow();
        assertThat(prodAFinal.getEstoqueDisponivel()).isEqualByComparingTo("70.000");
        assertThat(prodAFinal.getEstoqueReservado()).isEqualByComparingTo("0.000");

        Produto prodBFinal = produtoRepository.findById(idB).orElseThrow();
        assertThat(prodBFinal.getEstoqueDisponivel()).isEqualByComparingTo("70.000");
        assertThat(prodBFinal.getEstoqueReservado()).isEqualByComparingTo("0.000");
    }

    private long obterDeadlocksServidor() {
        try {
            jdbcTemplate.execute("SELECT pg_stat_force_next_flush()");
        } catch (Exception ignored) {
            // Em versões anteriores ao PG 15 ou sem permissão de force flush
        }
        Long count = jdbcTemplate.queryForObject(
                "SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()", Long.class);
        return count != null ? count : 0L;
    }

    private static boolean isDeadlock(Throwable t) {
        Throwable root = NestedExceptionUtils.getRootCause(t);
        return root instanceof SQLException sqlEx && "40P01".equals(sqlEx.getSQLState());
    }

    private static boolean isErroEsperadoPerdedor(Throwable t) {
        // Erro esperado quando a outra thread comitou antes:
        // Se cancelarVendaExpirada perdeu: BusinessException "não é elegível para cancelamento por expiração no status: CONCLUIDA"
        // Se handlePagamentoConfirmado perdeu: BusinessException "A venda não aceita pagamentos no status: CANCELADA" (ou OptimisticLockingFailureException)
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof BusinessException be) {
                String msg = be.getMessage();
                if (msg != null && (msg.contains("não é elegível para cancelamento") || msg.contains("não aceita pagamentos"))) {
                    return true;
                }
            }
            // Documentação A9: Aceita OptimisticLockingFailureException em qualquer ponto da cadeia.
            // O catch (Exception) do VendaEventHandler embrulha o erro (efeito colateral pré-existente do handler),
            // impedindo que o @Retryable de OLFE do handler o intercepte diretamente.
            if (cause instanceof org.springframework.dao.OptimisticLockingFailureException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
