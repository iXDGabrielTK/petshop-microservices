package inv.it;

import inv.InvServiceApplication;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.finance.domain.model.TipoLancamento;
import inv.finance.infrastructure.messaging.LancamentoRegistradoEvent;
import inv.finance.infrastructure.messaging.VendaEstornadaEventListener;
import inv.finance.infrastructure.messaging.VendaPagaEventListener;
import inv.finance.infrastructure.persistence.LancamentoFinanceiroRepository;
import inv.shared.event.VendaEstornadaEvent;
import inv.shared.event.VendaPagaEvent;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Suíte de concorrência e idempotência no Ledger Financeiro (Teste 6 do DEBT-ARCH-003 / E4).
 * Valida F1, Q6: entregas simultâneas de VendaPagaEvent e VendaEstornadaEvent com chave determinística,
 * sem UnexpectedRollbackException/DataIntegrityViolationException, com emissão única de LancamentoRegistradoEvent
 * por operação e reconciliação contábil integral em financial_projection_venda e financial_projection_diaria.
 */
@RecordApplicationEvents
@Testcontainers
@SpringBootTest(classes = InvServiceApplication.class)
class LedgerConcorrenciaIdempotenciaIT {

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
    private VendaPagaEventListener vendaPagaEventListener;

    @Autowired
    private VendaEstornadaEventListener vendaEstornadaEventListener;

    @Autowired
    private LancamentoFinanceiroRepository lancamentoRepository;

    @Autowired
    private VendaRepository vendaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
        applicationEvents.clear();
    }

    @Test
    @DisplayName("Teste 6 (F1, Q6) — Entregas simultâneas do mesmo uuidReferencia no Finance geram lançamento único e conciliado")
    void entregasSimultaneasMesmoUuidReferenciaNoFinanceGeraLancamentoUnicoEConciliado() throws Exception {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        // 1. Criar venda para satisfazer FK em lancamentos_financeiros
        Venda venda = vendaRepository.save(new Venda());
        Long vendaId = venda.getId();
        BigDecimal valor = new BigDecimal("100.00");
        long deadlocksInicio = obterDeadlocksServidor();

        // ==========================================
        // FASE 1: Concorrência em VendaPagaEvent (CREDITO)
        // ==========================================
        UUID refCredito = UUID.randomUUID();
        VendaPagaEvent eventCredito = new VendaPagaEvent(vendaId, refCredito, valor);

        int totalThreads = 10;
        CyclicBarrier barreiraCredito = new CyclicBarrier(totalThreads);
        Queue<Throwable> errosCredito = new ConcurrentLinkedQueue<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(totalThreads)) {
            for (int i = 0; i < totalThreads; i++) {
                pool.submit(() -> {
                    try {
                        barreiraCredito.await(10, TimeUnit.SECONDS);
                        txTemplate.executeWithoutResult(status -> vendaPagaEventListener.onVendaPaga(eventCredito));
                    } catch (Throwable t) {
                        errosCredito.add(t);
                    }
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        // Zero exceções (nenhuma UnexpectedRollbackException ou DataIntegrityViolationException)
        assertThat(errosCredito).isEmpty();

        // Exatamente 1 lançamento de CREDITO com valor 100.00
        Long countCredito = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lancamentos_financeiros WHERE venda_id = ? AND tipo = 'CREDITO' AND referencia = ?",
                Long.class, vendaId, refCredito);
        assertThat(countCredito).isEqualTo(1L);

        // Exatamente 1 LancamentoRegistradoEvent emitido para a operação de CREDITO
        List<LancamentoRegistradoEvent> eventosCredito = applicationEvents.stream(LancamentoRegistradoEvent.class)
                .filter(e -> vendaId.equals(e.vendaId()) && e.tipo() == TipoLancamento.CREDITO)
                .toList();
        assertThat(eventosCredito).hasSize(1);
        assertThat(eventosCredito.getFirst().valorAssinado()).isEqualByComparingTo("100.00");

        // Validação da projeção de venda pós-crédito via Awaitility (listener assíncrono)
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            BigDecimal saldo = jdbcTemplate.queryForObject(
                    "SELECT saldo FROM financial_projection_venda WHERE venda_id = ?",
                    BigDecimal.class, vendaId);
            assertThat(saldo).isNotNull().isEqualByComparingTo("100.00");
        });

        // ==========================================
        // FASE 2: Concorrência em VendaEstornadaEvent (ESTORNO)
        // ==========================================
        UUID refEstorno = UUID.nameUUIDFromBytes(("ESTORNO:" + vendaId).getBytes(StandardCharsets.UTF_8));
        VendaEstornadaEvent eventEstorno = new VendaEstornadaEvent(vendaId, refEstorno, valor);

        CyclicBarrier barreiraEstorno = new CyclicBarrier(totalThreads);
        Queue<Throwable> errosEstorno = new ConcurrentLinkedQueue<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(totalThreads)) {
            for (int i = 0; i < totalThreads; i++) {
                pool.submit(() -> {
                    try {
                        barreiraEstorno.await(10, TimeUnit.SECONDS);
                        vendaEstornadaEventListener.handleVendaEstornada(eventEstorno);
                    } catch (Throwable t) {
                        errosEstorno.add(t);
                    }
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        // Zero exceções
        assertThat(errosEstorno).isEmpty();

        // Exatamente 1 lançamento de ESTORNO com valor -100.00
        Long countEstorno = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lancamentos_financeiros WHERE venda_id = ? AND tipo = 'ESTORNO' AND referencia = ?",
                Long.class, vendaId, refEstorno);
        assertThat(countEstorno).isEqualTo(1L);

        // Exatamente 1 LancamentoRegistradoEvent emitido para a operação de ESTORNO (e 2 no total da venda)
        List<LancamentoRegistradoEvent> eventosEstorno = applicationEvents.stream(LancamentoRegistradoEvent.class)
                .filter(e -> vendaId.equals(e.vendaId()) && e.tipo() == TipoLancamento.ESTORNO)
                .toList();
        assertThat(eventosEstorno).hasSize(1);
        assertThat(eventosEstorno.getFirst().valorAssinado()).isEqualByComparingTo("-100.00");

        long totalEventosVenda = applicationEvents.stream(LancamentoRegistradoEvent.class)
                .filter(e -> vendaId.equals(e.vendaId()))
                .count();
        assertThat(totalEventosVenda).isEqualTo(2L);

        // Total de lançamentos na tabela do ledger para esta venda: exatamente 2 (1 CREDITO + 1 ESTORNO)
        Long totalLancamentos = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lancamentos_financeiros WHERE venda_id = ?",
                Long.class, vendaId);
        assertThat(totalLancamentos).isEqualTo(2L);

        // ==========================================
        // FASE 3: Reconciliação Contábil Integral
        // ==========================================

        // 1. Projeção por Venda (financial_projection_venda): saldo = 0.00, total_creditos = 100.00, total_estornos = 100.00
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Map<String, Object> projVenda = jdbcTemplate.queryForMap(
                    "SELECT saldo, total_creditos, total_estornos FROM financial_projection_venda WHERE venda_id = ?",
                    vendaId);
            assertThat((BigDecimal) projVenda.get("saldo")).isEqualByComparingTo("0.00");
            assertThat((BigDecimal) projVenda.get("total_creditos")).isEqualByComparingTo("100.00");
            assertThat((BigDecimal) projVenda.get("total_estornos")).isEqualByComparingTo("100.00");
        });

        // 2. Projeção Diária (financial_projection_diaria): filtro explícito por data_referencia do lançamento
        LocalDate dataReferencia = jdbcTemplate.queryForObject(
                "SELECT data_hora::date FROM lancamentos_financeiros WHERE venda_id = ? AND referencia = ?",
                LocalDate.class, vendaId, refCredito);
        assertThat(dataReferencia).isNotNull();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Map<String, Object> projDiaria = jdbcTemplate.queryForMap(
                    "SELECT total_creditos, total_debitos, (total_creditos - total_debitos) AS liquida FROM financial_projection_diaria WHERE data_referencia = ?",
                    dataReferencia);
            assertThat((BigDecimal) projDiaria.get("total_creditos")).isEqualByComparingTo("100.00");
            assertThat((BigDecimal) projDiaria.get("total_debitos")).isEqualByComparingTo("100.00");
            assertThat((BigDecimal) projDiaria.get("liquida")).isEqualByComparingTo("0.00");
        });

        // 3. Reconciliação com SUM(valor) do Ledger
        BigDecimal totalLedger = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(valor), 0) FROM lancamentos_financeiros WHERE venda_id = ?",
                BigDecimal.class, vendaId);
        assertThat(totalLedger).isNotNull().isEqualByComparingTo("0.00");

        // 4. Invariante 1 — Ausência física de deadlocks aferida no servidor PostgreSQL
        long deadlocksFim = obterDeadlocksServidor();
        assertThat(deadlocksFim - deadlocksInicio)
                .withFailMessage("O servidor PostgreSQL registrou deadlocks durante as entregas concorrentes no Ledger!")
                .isZero();
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
