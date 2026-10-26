package inv.it;

import common.exception.BusinessException;
import inv.InvServiceApplication;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.checkout.infrastructure.web.dto.ItemVendaRequest;
import inv.checkout.infrastructure.web.dto.VendaRequest;
import inv.checkout.usecase.VendaService;
import inv.inventory.domain.model.MovimentacaoEstoque;
import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.TipoMovimentacao;
import inv.inventory.domain.model.UnidadeMedida;
import inv.inventory.infrastructure.persistence.MovimentacaoRepository;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.ConcurrencyFailureException;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validação de concorrência multi-item pesada: 50 threads disputando carrinhos com produtos de disponibilidades distintas.
 * Alterna entre [A, B] e [B, A], provando ausência de deadlock (40P01) e rollback atômico integral do lote.
 */
@Testcontainers
@SpringBootTest(classes = InvServiceApplication.class)
class EstoqueMultiItemConcorrenciaIT {

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
    private ProdutoRepository produtoRepository;

    @Autowired
    private VendaRepository vendaRepository;

    @Autowired
    private MovimentacaoRepository movimentacaoRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long idA;
    private Long idB;
    private static final String NOME_PRODUTO_A = "Produto A Escasso Concorrencia";
    private static final String NOME_PRODUTO_B = "Produto B Abundante Concorrencia";

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

        Produto pA = new Produto();
        pA.setNome(NOME_PRODUTO_A);
        pA.setCodigoBarras("7898888000010");
        pA.setUnidadeMedida(UnidadeMedida.UN);
        pA.setPrecoVenda(new BigDecimal("20.00"));
        pA.setEstoqueDisponivel(new BigDecimal("5.000"));
        pA.setEstoqueReservado(new BigDecimal("0.000"));
        pA.setEstoqueMinimo(new BigDecimal("1.000"));
        Produto salvoA = produtoRepository.saveAndFlush(pA);
        this.idA = salvoA.getId();

        Produto pB = new Produto();
        pB.setNome(NOME_PRODUTO_B);
        pB.setCodigoBarras("7898888000020");
        pB.setUnidadeMedida(UnidadeMedida.UN);
        pB.setPrecoVenda(new BigDecimal("10.00"));
        pB.setEstoqueDisponivel(new BigDecimal("1000.000"));
        pB.setEstoqueReservado(new BigDecimal("0.000"));
        pB.setEstoqueMinimo(new BigDecimal("10.000"));
        Produto salvoB = produtoRepository.saveAndFlush(pB);
        this.idB = salvoB.getId();
    }

    @Test
    @DisplayName("E4 — 50 threads alternando carrinhos [A, B] e [B, A]: 5 sucessos, 45 BusinessException, 0 deadlocks (40P01) e rollback atômico")
    void deveExecutar50ThreadsMultiItemSemDeadlockEComRollbackIntegral() throws InterruptedException {
        int totalThreads = 50;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(totalThreads);

        AtomicInteger sucessos = new AtomicInteger();
        AtomicInteger falhasEstoque = new AtomicInteger();
        Queue<Throwable> deadlocks = new ConcurrentLinkedQueue<>();
        Queue<Throwable> outrosErros = new ConcurrentLinkedQueue<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(totalThreads)) {
            for (int i = 0; i < totalThreads; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        startGate.await();
                        List<ItemVendaRequest> itens = (idx % 2 == 0)
                                ? List.of(new ItemVendaRequest(idA, new BigDecimal("1.000")), new ItemVendaRequest(idB, new BigDecimal("1.000")))
                                : List.of(new ItemVendaRequest(idB, new BigDecimal("1.000")), new ItemVendaRequest(idA, new BigDecimal("1.000")));

                        vendaService.iniciarVenda(new VendaRequest(itens));
                        sucessos.incrementAndGet();
                    } catch (BusinessException be) {
                        if (be.getMessage().contains("Estoque insuficiente para o produto: " + NOME_PRODUTO_A)) {
                            falhasEstoque.incrementAndGet();
                        } else {
                            outrosErros.add(be);
                        }
                    } catch (Throwable t) {
                        if (isDeadlock(t)) {
                            deadlocks.add(t);
                        } else {
                            outrosErros.add(t);
                        }
                    } finally {
                        endGate.countDown();
                    }
                });
            }

            startGate.countDown();
            boolean concluido = endGate.await(45, TimeUnit.SECONDS);
            assertThat(concluido).withFailMessage("Timeout aguardando as 50 threads").isTrue();
        }

        // 1. Asserções de ausência de deadlocks e outros erros de concorrência
        assertThat(deadlocks).isEmpty();
        assertThat(outrosErros).isEmpty();

        // 2. Exatamente 5 sucessos e 45 falhas por insuficiência no produto A
        assertThat(sucessos.get()).isEqualTo(5);
        assertThat(falhasEstoque.get()).isEqualTo(45);

        // 3. Estado final do banco: produto A zerado no disponível, produto B reservou exatamente 5
        Produto prodAFinal = produtoRepository.findById(idA).orElseThrow();
        assertThat(prodAFinal.getEstoqueDisponivel()).isEqualByComparingTo("0.000");
        assertThat(prodAFinal.getEstoqueReservado()).isEqualByComparingTo("5.000");

        Produto prodBFinal = produtoRepository.findById(idB).orElseThrow();
        assertThat(prodBFinal.getEstoqueDisponivel()).isEqualByComparingTo("995.000");
        assertThat(prodBFinal.getEstoqueReservado()).isEqualByComparingTo("5.000");

        // 4. Prova de rollback atômico: exatamente 5 vendas persistidas e exatamente 10 movimentações RESERVA
        assertThat(vendaRepository.count()).isEqualTo(5L);

        List<MovimentacaoEstoque> movimentacoesReserva = movimentacaoRepository.findAll().stream()
                .filter(m -> m.getTipo() == TipoMovimentacao.RESERVA)
                .toList();
        assertThat(movimentacoesReserva).hasSize(10);
    }

    private static boolean isDeadlock(Throwable t) {
        Throwable root = NestedExceptionUtils.getRootCause(t);
        if (root instanceof SQLException sqlEx && "40P01".equals(sqlEx.getSQLState())) {
            return true;
        }
        if (t instanceof ConcurrencyFailureException cfe && cfe.getMessage() != null && cfe.getMessage().contains("deadlock")) {
            return true;
        }
        return t.getMessage() != null && t.getMessage().toLowerCase().contains("deadlock");
    }
}
