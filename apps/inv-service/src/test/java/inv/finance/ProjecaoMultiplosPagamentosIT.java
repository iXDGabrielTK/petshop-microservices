package inv.finance;

import inv.InvServiceApplication;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.finance.domain.model.LancamentoFinanceiro;
import inv.finance.domain.model.TipoLancamento;
import inv.finance.infrastructure.jobs.ReparoProjecaoVendaJob;
import inv.finance.infrastructure.messaging.FinancialProjectionListener;
import inv.finance.infrastructure.messaging.LancamentoRegistradoEvent;
import inv.finance.infrastructure.messaging.VendaPagaEventListener;
import inv.finance.infrastructure.persistence.LancamentoFinanceiroRepository;
import inv.finance.infrastructure.persistence.ReconciliacaoRepository;
import inv.finance.infrastructure.web.dto.DivergenciaResult;
import inv.finance.usecase.ReconciliacaoService;
import inv.shared.event.VendaPagaEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
@SpringBootTest(classes = InvServiceApplication.class)
class ProjecaoMultiplosPagamentosIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("inv_db_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @MockBean
    private JwtDecoder jwtDecoder;

    @MockBean
    private RabbitTemplate rabbitTemplate;

    @MockBean
    private ConnectionFactory connectionFactory;

    @Autowired
    private VendaPagaEventListener vendaPagaEventListener;

    @Autowired
    private FinancialProjectionListener financialProjectionListener;

    @Autowired
    private ReparoProjecaoVendaJob reparoProjecaoVendaJob;

    @Autowired
    private ReconciliacaoService reconciliacaoService;

    @Autowired
    private ReconciliacaoRepository reconciliacaoRepository;

    @Autowired
    private LancamentoFinanceiroRepository lancamentoRepository;

    @Autowired
    private VendaRepository vendaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM financial_reconciliation_checkpoint");
        jdbcTemplate.update("DELETE FROM financial_projection_checkpoint_v2");
        jdbcTemplate.update("DELETE FROM financial_projection_checkpoint");
        jdbcTemplate.update("DELETE FROM financial_projection_venda");
        jdbcTemplate.update("DELETE FROM projection_retry_queue");
        lancamentoRepository.deleteAll();
        vendaRepository.deleteAll();
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (F1): Dois pagamentos na mesma venda devem somar saldos corretamente e reprocessamento não duplica")
    void deveProcessarMultiplosPagamentosComIdempotenciaESaldosCorretos() {
        // 1. Criar uma venda
        Venda venda = vendaRepository.save(new Venda());
        Long vendaId = venda.getId();

        UUID ref1 = UUID.randomUUID();
        UUID ref2 = UUID.randomUUID();
        BigDecimal valor1 = new BigDecimal("100.00");
        BigDecimal valor2 = new BigDecimal("50.00");

        // 2. Simular chegada de 2 pagamentos na mesma venda via VendaPagaEventListener
        vendaPagaEventListener.onVendaPaga(new VendaPagaEvent(vendaId, ref1, valor1));
        vendaPagaEventListener.onVendaPaga(new VendaPagaEvent(vendaId, ref2, valor2));

        // 3. Validar que o ledger registrou dois lançamentos distintos com IDs próprios
        List<LancamentoFinanceiro> lancamentos = lancamentoRepository.findAll();
        assertThat(lancamentos).hasSize(2);

        LancamentoFinanceiro lanc1 = lancamentos.stream().filter(l -> l.getReferencia().equals(ref1)).findFirst().orElseThrow();
        LancamentoFinanceiro lanc2 = lancamentos.stream().filter(l -> l.getReferencia().equals(ref2)).findFirst().orElseThrow();

        assertThat(lanc1.getId()).isNotNull();
        assertThat(lanc2.getId()).isNotNull();
        assertThat(lanc1.getId()).isNotEqualTo(lanc2.getId());
        assertThat(lanc1.getVendaId()).isEqualTo(vendaId);
        assertThat(lanc2.getVendaId()).isEqualTo(vendaId);

        // 4. Processar os eventos de projeção CQRS para cada lançamento
        LancamentoRegistradoEvent event1 = new LancamentoRegistradoEvent(
                lanc1.getId(), lanc1.getVendaId(), lanc1.getValor(), lanc1.getTipo());
        LancamentoRegistradoEvent event2 = new LancamentoRegistradoEvent(
                lanc2.getId(), lanc2.getVendaId(), lanc2.getValor(), lanc2.getTipo());

        financialProjectionListener.processarComIdempotencia(event1);
        financialProjectionListener.processarComIdempotencia(event2);

        // 5. Validar que financial_projection_venda tem saldo = 150.00 (soma exata dos 2 pagamentos)
        Map<String, Object> projecao = jdbcTemplate.queryForMap(
                "SELECT saldo, total_creditos, total_estornos FROM financial_projection_venda WHERE venda_id = ?",
                vendaId);

        assertThat((BigDecimal) projecao.get("saldo")).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat((BigDecimal) projecao.get("total_creditos")).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat((BigDecimal) projecao.get("total_estornos")).isEqualByComparingTo(BigDecimal.ZERO);

        // 6. Validar que o checkpoint_v2 contém os dois lançamentos individualmente
        Integer countCp1 = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM financial_projection_checkpoint_v2 WHERE lancamento_id = ?",
                Integer.class, lanc1.getId());
        Integer countCp2 = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM financial_projection_checkpoint_v2 WHERE lancamento_id = ?",
                Integer.class, lanc2.getId());

        assertThat(countCp1).isEqualTo(1);
        assertThat(countCp2).isEqualTo(1);

        // 7. Testar idempotência: reprocessar os dois eventos não deve duplicar o saldo nem falhar
        financialProjectionListener.processarComIdempotencia(event1);
        financialProjectionListener.processarComIdempotencia(event2);

        Map<String, Object> projecaoPosReprocessamento = jdbcTemplate.queryForMap(
                "SELECT saldo, total_creditos, total_estornos FROM financial_projection_venda WHERE venda_id = ?",
                vendaId);

        assertThat((BigDecimal) projecaoPosReprocessamento.get("saldo")).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat((BigDecimal) projecaoPosReprocessamento.get("total_creditos")).isEqualByComparingTo(new BigDecimal("150.00"));
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (F1): Job de reparo deve recalcular a projeção e zerar divergências na reconciliação")
    void deveExecutarJobDeReparoECorrigirProjecaoComZeroDivergenciasNaReconciliacao() {
        // 1. Criar uma venda com 2 lançamentos no ledger (total R$ 200.00)
        Venda venda = vendaRepository.save(new Venda());
        Long vendaId = venda.getId();

        LancamentoFinanceiro lanc1 = lancamentoRepository.save(new LancamentoFinanceiro(
                vendaId, UUID.randomUUID(), TipoLancamento.CREDITO, new BigDecimal("120.00")));
        LancamentoFinanceiro lanc2 = lancamentoRepository.save(new LancamentoFinanceiro(
                vendaId, UUID.randomUUID(), TipoLancamento.CREDITO, new BigDecimal("80.00")));

        long maxLancamentoId = Math.max(lanc1.getId(), lanc2.getId());

        // 2. Simular estado corrompido pré-correção F1 (projeção gravou apenas o 1º pagamento = R$ 120.00)
        jdbcTemplate.update("""
            INSERT INTO financial_projection_venda (venda_id, saldo, total_creditos, total_estornos, ultima_atualizacao)
            VALUES (?, 120.00, 120.00, 0.00, CURRENT_TIMESTAMP)
            """, vendaId);
        jdbcTemplate.update("INSERT INTO financial_projection_checkpoint_v2 (lancamento_id) VALUES (?)", lanc1.getId());

        // 3. Auditar antes do reparo: deve constar divergência de R$ 80.00
        List<DivergenciaResult> divergenciasAntes = reconciliacaoRepository.auditarDivergencias(0, maxLancamentoId);
        assertThat(divergenciasAntes).hasSize(1);
        DivergenciaResult div = divergenciasAntes.getFirst();
        assertThat(div.getVendaId()).isEqualTo(vendaId);
        assertThat(div.getSaldoProjetado()).isEqualByComparingTo(new BigDecimal("120.00"));
        assertThat(div.getSaldoReal()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(div.getDivergencia()).isEqualByComparingTo(new BigDecimal("80.00"));

        // 4. Executar o job de reparo atômico
        int atualizadas = reparoProjecaoVendaJob.executarReparo();
        assertThat(atualizadas).isGreaterThanOrEqualTo(1);

        // 5. Validar que a projeção agora reflete o saldo real recalculado (R$ 200.00)
        Map<String, Object> projecaoCorrigida = jdbcTemplate.queryForMap(
                "SELECT saldo, total_creditos, total_estornos FROM financial_projection_venda WHERE venda_id = ?",
                vendaId);
        assertThat((BigDecimal) projecaoCorrigida.get("saldo")).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat((BigDecimal) projecaoCorrigida.get("total_creditos")).isEqualByComparingTo(new BigDecimal("200.00"));

        // 6. Validar que ambos os lançamentos estão registrados no checkpoint_v2
        Integer cp1 = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM financial_projection_checkpoint_v2 WHERE lancamento_id = ?",
                Integer.class, lanc1.getId());
        Integer cp2 = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM financial_projection_checkpoint_v2 WHERE lancamento_id = ?",
                Integer.class, lanc2.getId());
        assertThat(cp1).isEqualTo(1);
        assertThat(cp2).isEqualTo(1);

        // 7. Executar ReconciliacaoService com watermark 0: CRITÉRIO DE ACEITE OBRIGATÓRIO (0 divergências)
        List<DivergenciaResult> divergenciasDepois =
                reconciliacaoService.executarReconciliacaoIncremental(0, maxLancamentoId);
        assertThat(divergenciasDepois).isEmpty();

        List<DivergenciaResult> auditoriaFinal = reconciliacaoRepository.auditarDivergencias(0, maxLancamentoId);
        assertThat(auditoriaFinal).isEmpty();
    }

    @Test
    @DisplayName("DEBT-ARCH-001 (F1): Job de reparo deve ser estritamente idempotente")
    void deveGarantirQueJobDeReparoEhIdempotente() {
        Venda venda = vendaRepository.save(new Venda());
        LancamentoFinanceiro lanc1 = lancamentoRepository.save(new LancamentoFinanceiro(
                venda.getId(), UUID.randomUUID(), TipoLancamento.CREDITO, new BigDecimal("75.50")));

        // 1ª execução
        reparoProjecaoVendaJob.executarReparo();

        Map<String, Object> projecao1 = jdbcTemplate.queryForMap(
                "SELECT saldo FROM financial_projection_venda WHERE venda_id = ?", venda.getId());
        assertThat((BigDecimal) projecao1.get("saldo")).isEqualByComparingTo(new BigDecimal("75.50"));

        // 2ª execução (idempotente)
        reparoProjecaoVendaJob.executarReparo();

        Map<String, Object> projecao2 = jdbcTemplate.queryForMap(
                "SELECT saldo FROM financial_projection_venda WHERE venda_id = ?", venda.getId());
        assertThat((BigDecimal) projecao2.get("saldo")).isEqualByComparingTo(new BigDecimal("75.50"));
    }
}
