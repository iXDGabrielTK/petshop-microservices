package inv.it;

import common.exception.BusinessException;
import inv.InvServiceApplication;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.checkout.infrastructure.web.dto.ItemVendaRequest;
import inv.checkout.infrastructure.web.dto.VendaRequest;
import inv.checkout.usecase.VendaService;
import inv.inventory.domain.model.Produto;
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
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
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
 * Prova da ordenação determinística Q3/F5 em lote (produtoId ASC) e controle negativo de detecção de deadlock 40P01.
 */
@Testcontainers
@SpringBootTest(classes = InvServiceApplication.class)
class CarrinhosInvertidosDeadlockIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withCommand("postgres", "-c", "deadlock_timeout=100ms");

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long idA;
    private Long idB;

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
        pA.setNome("Produto Deadlock Test A");
        pA.setCodigoBarras("7897777000010");
        pA.setUnidadeMedida(UnidadeMedida.UN);
        pA.setPrecoVenda(new BigDecimal("30.00"));
        pA.setEstoqueDisponivel(new BigDecimal("100.000"));
        pA.setEstoqueReservado(new BigDecimal("0.000"));
        pA.setEstoqueMinimo(new BigDecimal("5.000"));
        Produto salvoA = produtoRepository.saveAndFlush(pA);
        this.idA = salvoA.getId();

        Produto pB = new Produto();
        pB.setNome("Produto Deadlock Test B");
        pB.setCodigoBarras("7897777000020");
        pB.setUnidadeMedida(UnidadeMedida.UN);
        pB.setPrecoVenda(new BigDecimal("40.00"));
        pB.setEstoqueDisponivel(new BigDecimal("100.000"));
        pB.setEstoqueReservado(new BigDecimal("0.000"));
        pB.setEstoqueMinimo(new BigDecimal("5.000"));
        Produto salvoB = produtoRepository.saveAndFlush(pB);
        this.idB = salvoB.getId();
    }

    @Test
    @DisplayName("Q3/F5 — 20 pares de carrinhos invertidos [A,B] vs [B,A] na CyclicBarrier executam com 0 deadlocks")
    void carrinhosInvertidosSimultaneosNaoCausamDeadlock() throws Exception {
        int pares = 20; // 40 threads
        CyclicBarrier barreira = new CyclicBarrier(pares * 2);
        AtomicInteger sucessos = new AtomicInteger();
        Queue<Throwable> erros = new ConcurrentLinkedQueue<>();

        long deadlocksServidorInicio = obterDeadlocksServidor();

        try (ExecutorService pool = Executors.newFixedThreadPool(pares * 2)) {
            for (int i = 0; i < pares; i++) {
                pool.submit(() -> executarNaBarreira(barreira, List.of(item(idA, "1.000"), item(idB, "1.000")), sucessos, erros));
                pool.submit(() -> executarNaBarreira(barreira, List.of(item(idB, "1.000"), item(idA, "1.000")), sucessos, erros));
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        // A1: Medição no servidor PostgreSQL garante que nenhuma tentativa sofreu 40P01 (independente de retry)
        long deadlocksServidorFim = obterDeadlocksServidor();
        assertThat(deadlocksServidorFim - deadlocksServidorInicio)
                .withFailMessage("O servidor PostgreSQL registrou deadlocks durante a execução concorrente de carrinhos invertidos!")
                .isZero();

        assertThat(erros).isEmpty();
        assertThat(sucessos.get()).isEqualTo(40);
        assertThat(produtoRepository.findById(idA).orElseThrow().getEstoqueReservado()).isEqualByComparingTo("40.000");
        assertThat(produtoRepository.findById(idB).orElseThrow().getEstoqueReservado()).isEqualByComparingTo("40.000");
    }

    @Test
    @DisplayName("Q3/F5 — Controle negativo: chamadas atômicas desordenadas sem ordenação global reproduzem deadlock 40P01")
    void controleNegativo_chamadasSemOrdenacaoCausamDeadlock40P01() throws Exception {
        CountDownLatch t1TravouA = new CountDownLatch(1);
        CountDownLatch t2TravouB = new CountDownLatch(1);
        CountDownLatch t1PediuB = new CountDownLatch(1);

        AtomicInteger deadlocks = new AtomicInteger();
        AtomicInteger sucessos = new AtomicInteger();
        Queue<Throwable> outrosErros = new ConcurrentLinkedQueue<>();

        TransactionTemplate tt1 = new TransactionTemplate(transactionManager);
        tt1.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        TransactionTemplate tt2 = new TransactionTemplate(transactionManager);
        tt2.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        long deadlocksServidorInicio = obterDeadlocksServidor();

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            // T1: Trava A -> Espera T2 travar B -> Pede B
            Future<?> f1 = pool.submit(() -> {
                try {
                    tt1.execute(status -> {
                        jdbcTemplate.execute("SET LOCAL deadlock_timeout = '100ms'");
                        produtoRepository.reservarEstoqueAtomo(idA, new BigDecimal("1.000"));
                        t1TravouA.countDown();

                        try {
                            if (!t2TravouB.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Timeout esperando T2 travar B");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }

                        t1PediuB.countDown();
                        // T1 pede B (que já está travado por T2) -> T1 suspende aguardando lock em B
                        produtoRepository.reservarEstoqueAtomo(idB, new BigDecimal("1.000"));
                        return null;
                    });
                    sucessos.incrementAndGet();
                } catch (Throwable t) {
                    if (isDeadlock40P01(t)) {
                        deadlocks.incrementAndGet();
                    } else {
                        outrosErros.add(t);
                    }
                }
            });

            // T2: Espera T1 travar A -> Trava B -> Espera T1 pedir B -> Pede A (Fecha ciclo de deadlock!)
            Future<?> f2 = pool.submit(() -> {
                try {
                    tt2.execute(status -> {
                        try {
                            if (!t1TravouA.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Timeout esperando T1 travar A");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }

                        jdbcTemplate.execute("SET LOCAL deadlock_timeout = '100ms'");
                        produtoRepository.reservarEstoqueAtomo(idB, new BigDecimal("1.000"));
                        t2TravouB.countDown();

                        try {
                            if (!t1PediuB.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Timeout esperando T1 pedir B");
                            }
                            // A7: Thread.sleep dispensável pois qualquer intercalação com latches produz exatamente 1 vítima
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }

                        // T2 pede A (que está travado por T1) -> Ciclo T1->T2->T1 formado!
                        produtoRepository.reservarEstoqueAtomo(idA, new BigDecimal("1.000"));
                        return null;
                    });
                    sucessos.incrementAndGet();
                } catch (Throwable t) {
                    if (isDeadlock40P01(t)) {
                        deadlocks.incrementAndGet();
                    } else {
                        outrosErros.add(t);
                    }
                }
            });

            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        // A1: Comprova no pg_stat_database que o servidor detectou e contabilizou exatamente 1 deadlock
        long deadlocksServidorFim = obterDeadlocksServidor();
        assertThat(deadlocksServidorFim - deadlocksServidorInicio)
                .withFailMessage("O controle negativo deve registrar exatamente 1 deadlock no servidor PostgreSQL")
                .isEqualTo(1L);

        // Deve ocorrer exatamente 1 deadlock (SQLState 40P01) e exatamente 1 sucesso (a transação sobrevivente)
        assertThat(outrosErros).isEmpty();
        assertThat(deadlocks.get()).isEqualTo(1);
        assertThat(sucessos.get()).isEqualTo(1);
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

    private void executarNaBarreira(CyclicBarrier barreira, List<ItemVendaRequest> itens,
                                    AtomicInteger sucessos, Queue<Throwable> erros) {
        try {
            barreira.await(10, TimeUnit.SECONDS);
            vendaService.iniciarVenda(new VendaRequest(itens));
            sucessos.incrementAndGet();
        } catch (Throwable t) {
            erros.add(t);
        }
    }

    private static ItemVendaRequest item(Long produtoId, String quantidade) {
        return new ItemVendaRequest(produtoId, new BigDecimal(quantidade));
    }

    private static boolean isDeadlock40P01(Throwable t) {
        if (!(t instanceof PessimisticLockingFailureException)) {
            return false;
        }
        Throwable root = NestedExceptionUtils.getRootCause(t);
        return root instanceof SQLException sqlEx && "40P01".equals(sqlEx.getSQLState());
    }
}
