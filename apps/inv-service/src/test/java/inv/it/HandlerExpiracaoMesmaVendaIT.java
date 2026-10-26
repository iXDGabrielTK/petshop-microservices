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
    @DisplayName("E4 — Pagamento e expiração da mesma venda disparados simultaneamente (CyclicBarrier, 30 repetições): sempre 1 vencedor, 0 deadlocks e saldos coerentes")
    void corridaPagamentoVsExpiracaoMesmaVendaDeveTerUnicoVencedorESaldosCoerentes() throws Exception {
        int repeticoes = 30;
        AtomicInteger concluidasCount = new AtomicInteger();
        AtomicInteger canceladasCount = new AtomicInteger();
        Queue<Throwable> deadlocks = new ConcurrentLinkedQueue<>();
        Queue<Throwable> errosInesperados = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < repeticoes; i++) {
            // 1. Cria produto com 10 unidades
            Produto produto = new Produto();
            produto.setNome("Produto Corrida Mesma Venda #" + i);
            produto.setCodigoBarras("7896666" + String.format("%06d", i));
            produto.setUnidadeMedida(UnidadeMedida.UN);
            produto.setPrecoVenda(new BigDecimal("50.00"));
            produto.setEstoqueDisponivel(new BigDecimal("10.000"));
            produto.setEstoqueReservado(new BigDecimal("0.000"));
            produto.setEstoqueMinimo(new BigDecimal("2.000"));
            produto = produtoRepository.saveAndFlush(produto);

            // 2. Inicia venda reservando 2 unidades (disp=8, res=2)
            Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(
                    new ItemVendaRequest(produto.getId(), new BigDecimal("2.000"))
            )));

            Long pagamentoId = pagamentoService.criarIntencaoPagamento(
                    venda.getId(), MetodoPagamento.PIX, new BigDecimal("100.00"), UUID.randomUUID());
            pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("100.00"), "NSU-RACE-" + i);

            final Long vendaId = venda.getId();
            final String payload = """
                    {"vendaId":%d,"pagamentoId":%d}
                    """.formatted(vendaId, pagamentoId);

            CyclicBarrier barreira = new CyclicBarrier(2);

            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                // Thread 1: Confirmação de pagamento (baixa definitiva)
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

                // Thread 2: Cancelamento por expiração (estorno)
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

            Produto prodAtualizado = produtoRepository.findById(produto.getId()).orElseThrow();
            List<MovimentacaoEstoque> movsVenda = movimentacaoRepository.findAll().stream()
                    .filter(m -> vendaId.equals(m.getVendaId()))
                    .toList();

            if (statusFinal == StatusVenda.CONCLUIDA) {
                concluidasCount.incrementAndGet();
                // Baixa realizada: reservado zerado, disponivel permanece em 8
                assertThat(prodAtualizado.getEstoqueReservado()).isEqualByComparingTo("0.000");
                assertThat(prodAtualizado.getEstoqueDisponivel()).isEqualByComparingTo("8.000");

                // Movimentações: RESERVA e SAIDA
                assertThat(movsVenda).extracting(MovimentacaoEstoque::getTipo)
                        .containsExactlyInAnyOrder(TipoMovimentacao.RESERVA, TipoMovimentacao.SAIDA);
            } else {
                canceladasCount.incrementAndGet();
                // Estorno realizado: reservado zerado, disponivel restaurado para 10
                assertThat(prodAtualizado.getEstoqueReservado()).isEqualByComparingTo("0.000");
                assertThat(prodAtualizado.getEstoqueDisponivel()).isEqualByComparingTo("10.000");

                // Movimentações: RESERVA e CANCELAMENTO_RESERVA
                assertThat(movsVenda).extracting(MovimentacaoEstoque::getTipo)
                        .containsExactlyInAnyOrder(TipoMovimentacao.RESERVA, TipoMovimentacao.CANCELAMENTO_RESERVA);
            }
        }

        // 4. Asserções finais globais
        assertThat(deadlocks).isEmpty();
        assertThat(errosInesperados).isEmpty();
        assertThat(concluidasCount.get() + canceladasCount.get()).isEqualTo(repeticoes);
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
            if (cause instanceof org.springframework.dao.OptimisticLockingFailureException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
