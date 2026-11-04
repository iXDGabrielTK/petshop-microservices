package inv.it;

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
import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.UnidadeMedida;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import inv.shared.config.RabbitMQConfig;
import inv.shared.domain.MetodoPagamento;
import inv.shared.outbox.repository.OutboxRepository;
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
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Suíte de corridas entre pagamento tardio em trânsito e cancelamento na mesma venda
 * (Teste 5 do DEBT-ARCH-003 / E4: Ordem A, Ordem B1, Ordem B2 e 20 repetições em CyclicBarrier).
 * Valida F2, F6, Q7 e a ausência física de deadlocks (40P01) no PostgreSQL.
 */
@Testcontainers
@SpringBootTest(classes = InvServiceApplication.class)
class CancelamentoPagamentoRaceIT {

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
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
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
    @DisplayName("Teste 5.1 (Ordem A) — Cancelamento adquire lock antes de pagamento tardio em trânsito")
    void ordemA_cancelamentoVenceLockAntesDePagamentoTardio() throws Exception {
        Produto prod = salvarProduto("7892000100010", "Produto Ordem A",
                new BigDecimal("100.00"), new BigDecimal("10.000"), new BigDecimal("1.000"));

        // 1. Iniciar e concluir venda com pag1
        Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(item(prod.getId(), "1.000"))));
        Long vendaId = venda.getId();
        Long pag1Id = pagamentoService.criarIntencaoPagamento(
                vendaId, MetodoPagamento.PIX, new BigDecimal("100.00"), UUID.randomUUID());
        pagamentoService.confirmarPagamentoWebhook(pag1Id, new BigDecimal("100.00"), "NSU-ORDEM-A-1");
        vendaEventHandler.handlePagamentoConfirmado("{\"vendaId\":" + vendaId + ",\"pagamentoId\":" + pag1Id + "}");

        // 2. Criar segundo pagamento pag2 confirmado no webhook mas não contabilizado
        Long pag2Id = pagamentoService.criarIntencaoPagamento(
                vendaId, MetodoPagamento.CARTAO_CREDITO, new BigDecimal("100.00"), UUID.randomUUID());
        pagamentoService.confirmarPagamentoWebhook(pag2Id, new BigDecimal("100.00"), "NSU-ORDEM-A-2");

        outboxRepository.deleteAll();
        double metricAntes = meterRegistry.counter(VendaEventHandler.METRICA_PAGAMENTO_EXCEDENTE).count();
        long deadlocksInicio = obterDeadlocksServidor();

        // 3. Ordem A: Cancelamento vence o lock em vendas; handler aguarda o cancelamento concluir
        CountDownLatch latchCancelDone = new CountDownLatch(1);
        Queue<Throwable> erros = new ConcurrentLinkedQueue<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            // Thread 1: Cancelamento
            pool.submit(() -> {
                try {
                    vendaService.cancelarVendaConcluida(vendaId);
                } catch (Throwable t) {
                    erros.add(t);
                } finally {
                    latchCancelDone.countDown();
                }
            });

            // Thread 2: Handler de pagamento tardio processa após desbloqueio
            pool.submit(() -> {
                try {
                    assertThat(latchCancelDone.await(5, TimeUnit.SECONDS)).isTrue();
                    vendaEventHandler.handlePagamentoConfirmado(
                            "{\"vendaId\":" + vendaId + ",\"pagamentoId\":" + pag2Id + "}");
                } catch (Throwable t) {
                    erros.add(t);
                }
            });

            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        long deadlocksFim = obterDeadlocksServidor();
        assertThat(deadlocksFim - deadlocksInicio).isZero();
        assertThat(erros).isEmpty();

        // Asserções Ordem A:
        Venda vendaFim = vendaRepository.findById(vendaId).orElseThrow();
        assertThat(vendaFim.getStatus()).isEqualTo(StatusVenda.CANCELADA);

        Pagamento pag1Fim = pagamentoRepository.findById(pag1Id).orElseThrow();
        assertThat(pag1Fim.getStatus()).isEqualTo(StatusPagamento.ESTORNADO);
        assertThat(pag1Fim.isContabilizado()).isTrue();

        Pagamento pag2Fim = pagamentoRepository.findById(pag2Id).orElseThrow();
        assertThat(pag2Fim.isExcedente()).isTrue();
        assertThat(pag2Fim.isContabilizado()).isFalse();

        double metricDepois = meterRegistry.counter(VendaEventHandler.METRICA_PAGAMENTO_EXCEDENTE).count();
        assertThat(metricDepois - metricAntes).isEqualTo(1.0);

        Produto prodFim = produtoRepository.findById(prod.getId()).orElseThrow();
        assertThat(prodFim.getEstoqueDisponivel()).isEqualByComparingTo("10.000");
        assertThat(prodFim.getEstoqueReservado()).isEqualByComparingTo("0.000");

        long outboxCount = outboxRepository.findAll().stream()
                .filter(o -> RabbitMQConfig.ROUTING_KEY_VENDA_ESTORNADA.equals(o.getRoutingKey()))
                .count();
        assertThat(outboxCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("Teste 5.2 (Ordem B1) — Handler vence lock antes de cancelamento em venda AGUARDANDO_PAGAMENTO")
    void ordemB1_handlerVenceLockAntesDeCancelamentoEmVendaAguardandoPagamento() throws Exception {
        Produto prod = salvarProduto("7892000100020", "Produto Ordem B1",
                new BigDecimal("80.00"), new BigDecimal("10.000"), new BigDecimal("1.000"));

        // 1. Venda aberta em AGUARDANDO_PAGAMENTO (reserva efetuada)
        Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(item(prod.getId(), "1.000"))));
        Long vendaId = venda.getId();
        Long pagId = pagamentoService.criarIntencaoPagamento(
                vendaId, MetodoPagamento.DINHEIRO, new BigDecimal("80.00"), UUID.randomUUID());
        pagamentoService.confirmarPagamentoWebhook(pagId, new BigDecimal("80.00"), "NSU-ORDEM-B1");

        // Estoque pré: disp = 9.000, res = 1.000
        Produto pPre = produtoRepository.findById(prod.getId()).orElseThrow();
        assertThat(pPre.getEstoqueDisponivel()).isEqualByComparingTo("9.000");
        assertThat(pPre.getEstoqueReservado()).isEqualByComparingTo("1.000");

        outboxRepository.deleteAll();
        long deadlocksInicio = obterDeadlocksServidor();

        // 2. Ordem B1: Handler adquire lock primeiro, conclui venda e baixa estoque; cancelamento cancela em seguida
        CountDownLatch latchHandlerDone = new CountDownLatch(1);
        Queue<Throwable> erros = new ConcurrentLinkedQueue<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            // Thread 1: Handler de confirmação (adquire lock primeiro, conclui venda)
            pool.submit(() -> {
                try {
                    vendaEventHandler.handlePagamentoConfirmado(
                            "{\"vendaId\":" + vendaId + ",\"pagamentoId\":" + pagId + "}");
                } catch (Throwable t) {
                    erros.add(t);
                } finally {
                    latchHandlerDone.countDown();
                }
            });

            // Thread 2: Cancelamento (aguarda handler concluir venda, depois cancela venda concluída)
            pool.submit(() -> {
                try {
                    assertThat(latchHandlerDone.await(5, TimeUnit.SECONDS)).isTrue();
                    vendaService.cancelarVendaConcluida(vendaId);
                } catch (Throwable t) {
                    erros.add(t);
                }
            });

            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        long deadlocksFim = obterDeadlocksServidor();
        assertThat(deadlocksFim - deadlocksInicio).isZero();
        assertThat(erros).isEmpty();

        // Asserções Ordem B1:
        Venda vendaFim = vendaRepository.findById(vendaId).orElseThrow();
        assertThat(vendaFim.getStatus()).isEqualTo(StatusVenda.CANCELADA);

        Pagamento pagFim = pagamentoRepository.findById(pagId).orElseThrow();
        assertThat(pagFim.getStatus()).isEqualTo(StatusPagamento.ESTORNADO);

        Produto prodFim = produtoRepository.findById(prod.getId()).orElseThrow();
        assertThat(prodFim.getEstoqueDisponivel()).isEqualByComparingTo("10.000");
        assertThat(prodFim.getEstoqueReservado()).isEqualByComparingTo("0.000");

        long outboxCount = outboxRepository.findAll().stream()
                .filter(o -> RabbitMQConfig.ROUTING_KEY_VENDA_ESTORNADA.equals(o.getRoutingKey()))
                .count();
        assertThat(outboxCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("Teste 5.3 (Ordem B2) — Handler vence lock em venda já CONCLUIDA com pagamento tardio")
    void ordemB2_handlerVenceLockAntesDeCancelamentoEmVendaConcluidaComPagamentoTardio() throws Exception {
        Produto prod = salvarProduto("7892000100030", "Produto Ordem B2",
                new BigDecimal("50.00"), new BigDecimal("10.000"), new BigDecimal("1.000"));

        // 1. Iniciar e concluir venda com pag1
        Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(item(prod.getId(), "1.000"))));
        Long vendaId = venda.getId();
        Long pag1Id = pagamentoService.criarIntencaoPagamento(
                vendaId, MetodoPagamento.PIX, new BigDecimal("50.00"), UUID.randomUUID());
        pagamentoService.confirmarPagamentoWebhook(pag1Id, new BigDecimal("50.00"), "NSU-ORDEM-B2-1");
        vendaEventHandler.handlePagamentoConfirmado("{\"vendaId\":" + vendaId + ",\"pagamentoId\":" + pag1Id + "}");

        // 2. Criar pag2 confirmado no webhook
        Long pag2Id = pagamentoService.criarIntencaoPagamento(
                vendaId, MetodoPagamento.DINHEIRO, new BigDecimal("50.00"), UUID.randomUUID());
        pagamentoService.confirmarPagamentoWebhook(pag2Id, new BigDecimal("50.00"), "NSU-ORDEM-B2-2");

        outboxRepository.deleteAll();
        double metricAntes = meterRegistry.counter(VendaEventHandler.METRICA_PAGAMENTO_EXCEDENTE).count();
        long deadlocksInicio = obterDeadlocksServidor();

        // 3. Ordem B2: Handler vence o lock em vendas concluídas; cancelamento cancela em seguida
        CountDownLatch latchHandlerDone = new CountDownLatch(1);
        Queue<Throwable> erros = new ConcurrentLinkedQueue<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            // Thread 1: Handler adquire lock primeiro, marca pag2 como excedente
            pool.submit(() -> {
                try {
                    vendaEventHandler.handlePagamentoConfirmado(
                            "{\"vendaId\":" + vendaId + ",\"pagamentoId\":" + pag2Id + "}");
                } catch (Throwable t) {
                    erros.add(t);
                } finally {
                    latchHandlerDone.countDown();
                }
            });

            // Thread 2: Cancelamento adquire lock após handler marcar pag2 como excedente
            pool.submit(() -> {
                try {
                    assertThat(latchHandlerDone.await(5, TimeUnit.SECONDS)).isTrue();
                    vendaService.cancelarVendaConcluida(vendaId);
                } catch (Throwable t) {
                    erros.add(t);
                }
            });

            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        long deadlocksFim = obterDeadlocksServidor();
        assertThat(deadlocksFim - deadlocksInicio).isZero();
        assertThat(erros).isEmpty();

        // Asserções Ordem B2:
        Venda vendaFim = vendaRepository.findById(vendaId).orElseThrow();
        assertThat(vendaFim.getStatus()).isEqualTo(StatusVenda.CANCELADA);

        Pagamento pag1Fim = pagamentoRepository.findById(pag1Id).orElseThrow();
        assertThat(pag1Fim.getStatus()).isEqualTo(StatusPagamento.ESTORNADO);

        Pagamento pag2Fim = pagamentoRepository.findById(pag2Id).orElseThrow();
        assertThat(pag2Fim.isExcedente()).isTrue();
        assertThat(pag2Fim.isContabilizado()).isFalse();

        double metricDepois = meterRegistry.counter(VendaEventHandler.METRICA_PAGAMENTO_EXCEDENTE).count();
        assertThat(metricDepois - metricAntes).isEqualTo(1.0);

        Produto prodFim = produtoRepository.findById(prod.getId()).orElseThrow();
        assertThat(prodFim.getEstoqueDisponivel()).isEqualByComparingTo("10.000");
        assertThat(prodFim.getEstoqueReservado()).isEqualByComparingTo("0.000");

        long outboxCount = outboxRepository.findAll().stream()
                .filter(o -> RabbitMQConfig.ROUTING_KEY_VENDA_ESTORNADA.equals(o.getRoutingKey()))
                .count();
        assertThat(outboxCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("Teste 5.4 (Bateria Livre) — 20 repetições de corrida livre entre pagamento tardio e cancelamento em CyclicBarrier(2)")
    void bateriaLivre_corridaPagamentoTardioVsCancelamentoVendaConcluida() throws Exception {
        Produto prod = salvarProduto("7892000100040", "Produto Bateria Livre",
                new BigDecimal("10.00"), new BigDecimal("100.000"), new BigDecimal("5.000"));

        int repeticoes = 20;
        double metricInicio = meterRegistry.counter(VendaEventHandler.METRICA_PAGAMENTO_EXCEDENTE).count();
        long deadlocksInicio = obterDeadlocksServidor();

        for (int r = 0; r < repeticoes; r++) {
            // Setup por repetição: venda concluída com pag1 e pag2 tardio em trânsito
            Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(item(prod.getId(), "1.000"))));
            Long vendaId = venda.getId();

            Long pag1Id = pagamentoService.criarIntencaoPagamento(
                    vendaId, MetodoPagamento.PIX, new BigDecimal("10.00"), UUID.randomUUID());
            pagamentoService.confirmarPagamentoWebhook(pag1Id, new BigDecimal("10.00"), "NSU-LIVRE-1-" + r);
            vendaEventHandler.handlePagamentoConfirmado("{\"vendaId\":" + vendaId + ",\"pagamentoId\":" + pag1Id + "}");

            Long pag2Id = pagamentoService.criarIntencaoPagamento(
                    vendaId, MetodoPagamento.DINHEIRO, new BigDecimal("10.00"), UUID.randomUUID());
            pagamentoService.confirmarPagamentoWebhook(pag2Id, new BigDecimal("10.00"), "NSU-LIVRE-2-" + r);

            CyclicBarrier barreira = new CyclicBarrier(2);
            Queue<Throwable> erros = new ConcurrentLinkedQueue<>();

            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                // Thread 1: Cancelamento
                pool.submit(() -> {
                    try {
                        barreira.await(10, TimeUnit.SECONDS);
                        vendaService.cancelarVendaConcluida(vendaId);
                    } catch (Throwable t) {
                        erros.add(t);
                    }
                });

                // Thread 2: Handler de pagamento tardio
                pool.submit(() -> {
                    try {
                        barreira.await(10, TimeUnit.SECONDS);
                        vendaEventHandler.handlePagamentoConfirmado(
                            "{\"vendaId\":" + vendaId + ",\"pagamentoId\":" + pag2Id + "}");
                    } catch (Throwable t) {
                        erros.add(t);
                    }
                });

                pool.shutdown();
                assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(erros).isEmpty();

            // Asserções invariantes da repetição:
            Venda vFim = vendaRepository.findById(vendaId).orElseThrow();
            assertThat(vFim.getStatus()).isEqualTo(StatusVenda.CANCELADA);

            Pagamento p1Fim = pagamentoRepository.findById(pag1Id).orElseThrow();
            assertThat(p1Fim.getStatus()).isEqualTo(StatusPagamento.ESTORNADO);

            Pagamento p2Fim = pagamentoRepository.findById(pag2Id).orElseThrow();
            assertThat(p2Fim.isExcedente()).isTrue();
            assertThat(p2Fim.isContabilizado()).isFalse();
        }

        long deadlocksFim = obterDeadlocksServidor();
        assertThat(deadlocksFim - deadlocksInicio).isZero();

        // O contador de pagamentos excedentes somou exatamente +20.0
        double metricFim = meterRegistry.counter(VendaEventHandler.METRICA_PAGAMENTO_EXCEDENTE).count();
        assertThat(metricFim - metricInicio).isEqualTo((double) repeticoes);

        // Saldo do produto totalmente recomposto para 100.000
        Produto prodFim = produtoRepository.findById(prod.getId()).orElseThrow();
        assertThat(prodFim.getEstoqueDisponivel()).isEqualByComparingTo("100.000");
        assertThat(prodFim.getEstoqueReservado()).isEqualByComparingTo("0.000");

        // Exatamente 20 eventos de estorno no Outbox
        long totalOutboxEstornos = outboxRepository.findAll().stream()
                .filter(o -> RabbitMQConfig.ROUTING_KEY_VENDA_ESTORNADA.equals(o.getRoutingKey()))
                .count();
        assertThat(totalOutboxEstornos).isEqualTo((long) repeticoes);
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
