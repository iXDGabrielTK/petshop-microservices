package inv.it;

import inv.InvServiceApplication;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.finance.domain.model.TipoLancamento;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Suíte de concorrência e idempotência no Ledger Financeiro (Teste 6 do DEBT-ARCH-003 / E4).
 * Valida F1, Q6: entregas simultâneas de VendaPagaEvent e VendaEstornadaEvent com chave determinística,
 * sem UnexpectedRollbackException/DataIntegrityViolationException, com reconciliação contábil integral
 * em financial_projection_venda e financial_projection_diaria.
 */
@Testcontainers
@SpringBootTest(classes = InvServiceApplication.class)
class LedgerConcorrenciaIdempotenciaIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
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
    }

    @Test
    @DisplayName("Teste 6 (F1, Q6) — Entregas simultâneas do mesmo uuidReferencia no Finance geram lançamento único e conciliado")
    void entregasSimultaneasMesmoUuidReferenciaNoFinanceGeraLancamentoUnicoEConciliado() throws Exception {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        // 1. Criar venda para satisfazer FK em lancamentos_financeiros
        Venda venda = vendaRepository.save(new Venda());
        Long vendaId = venda.getId();
        BigDecimal valor = new BigDecimal("100.00");

        // ==========================================
        // FASE 1: Concorrência em VendaPagaEvent (CREDITO)
        // ==========================================
        UUID refCredito = UUID.randomUUID();
        VendaPagaEvent eventCredito = new VendaPagaEvent(vendaId, refCredito, valor);

        CyclicBarrier barreiraCredito = new CyclicBarrier(2);
        Queue<Throwable> errosCredito = new ConcurrentLinkedQueue<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
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

        CyclicBarrier barreiraEstorno = new CyclicBarrier(2);
        Queue<Throwable> errosEstorno = new ConcurrentLinkedQueue<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
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

        // 2. Projeção Diária (financial_projection_diaria): total_creditos = 100.00, total_debitos = 100.00, receita líquida = 0.00
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Map<String, Object> projDiaria = jdbcTemplate.queryForMap(
                    "SELECT total_creditos, total_debitos, (total_creditos - total_debitos) AS liquida FROM financial_projection_diaria LIMIT 1");
            assertThat((BigDecimal) projDiaria.get("total_creditos")).isEqualByComparingTo("100.00");
            assertThat((BigDecimal) projDiaria.get("total_debitos")).isEqualByComparingTo("100.00");
            assertThat((BigDecimal) projDiaria.get("liquida")).isEqualByComparingTo("0.00");
        });

        // 3. Reconciliação com SUM(valor) do Ledger
        BigDecimal totalLedger = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(valor), 0) FROM lancamentos_financeiros WHERE venda_id = ?",
                BigDecimal.class, vendaId);
        assertThat(totalLedger).isNotNull().isEqualByComparingTo("0.00");
    }
}
