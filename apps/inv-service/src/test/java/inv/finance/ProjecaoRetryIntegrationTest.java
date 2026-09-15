package inv.finance;

import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.finance.domain.model.LancamentoFinanceiro;
import inv.finance.domain.model.ProjectionRetry;
import inv.finance.domain.model.TipoLancamento;
import inv.finance.infrastructure.jobs.ProjectionRetryScheduler;
import inv.finance.infrastructure.messaging.FinancialProjectionListener;
import inv.finance.infrastructure.messaging.LancamentoRegistradoEvent;
import inv.finance.infrastructure.persistence.FinancialProjectionRepository;
import inv.finance.infrastructure.persistence.LancamentoFinanceiroRepository;
import inv.finance.infrastructure.persistence.ProjectionRetryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Teste de concorrência, consistência eventual e auto-cura conforme especificação do ADR-0006 (Seção 6).
 * Valida o padrão CQRS com projeções assíncronas e resiliência via tabela de retry (projection_retry_queue)
 * sob falha transitória induzida e subsequente reprocessamento idempotente pelo scheduler.
 */
@Testcontainers
@SpringBootTest
class ProjecaoRetryIntegrationTest {

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
    private FinancialProjectionRepository projectionRepository;

    @Autowired
    private FinancialProjectionListener projectionListener;

    @Autowired
    private ProjectionRetryScheduler projectionRetryScheduler;

    @Autowired
    private LancamentoFinanceiroRepository lancamentoRepository;

    @Autowired
    private ProjectionRetryRepository retryRepository;

    @Autowired
    private VendaRepository vendaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM financial_projection_checkpoint");
        jdbcTemplate.update("DELETE FROM financial_projection_venda");
        retryRepository.deleteAll();
        lancamentoRepository.deleteAll();
        vendaRepository.deleteAll();
        Mockito.reset(projectionRepository);
    }

    @Test
    @DisplayName("ADR-0006 Seção 6: Falha transitória na projeção deve enfileirar na retry queue e auto-cura deve restabelecer projeção com idempotência")
    void deveSimularFalhaTransitoriaEAutoCuraComIdempotencia() {
        // 1. Setup: Registrar uma venda e um lançamento de R$ 150,00 no ledger
        Venda venda = vendaRepository.save(new Venda());
        Long vendaId = venda.getId();

        BigDecimal valor = new BigDecimal("150.00");
        LancamentoFinanceiro lancamento = new LancamentoFinanceiro(
                vendaId,
                UUID.randomUUID(),
                TipoLancamento.CREDITO,
                valor
        );
        LancamentoFinanceiro lancamentoSalvo = lancamentoRepository.save(lancamento);
        Long lancamentoId = lancamentoSalvo.getId();

        LancamentoRegistradoEvent event = new LancamentoRegistradoEvent(
                lancamentoId,
                vendaId,
                valor,
                TipoLancamento.CREDITO
        );

        // 2. Injeção de Falha: Simula indisponibilidade transitória no Upsert do repositório
        Mockito.doThrow(new RuntimeException("Simulação de falha transitória / lock de banco"))
                .when(projectionRepository)
                .upsertProjection(Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any());

        // Processa evento com falha induzida
        projectionListener.processarComIdempotencia(event);

        // 3. Validação Intermediária
        // a) Lançamento existe no ledger
        assertTrue(lancamentoRepository.existsById(lancamentoId), "O lançamento deve existir no ledger.");

        // b) Registro gravado na tabela projection_retry_queue com status PENDENTE
        List<ProjectionRetry> retries = retryRepository.findAll();
        assertEquals(1, retries.size(), "Deve haver exatamente 1 registro na fila de retry.");
        ProjectionRetry retryItem = retries.getFirst();
        assertEquals(lancamentoId, retryItem.getLancamentoId());
        assertEquals("PENDENTE", retryItem.getStatus());
        assertNotNull(retryItem.getErro(), "O erro da falha deve estar registrado.");

        // c) Projeção financial_projection_venda e checkpoint ainda NÃO devem refletir o valor (isolamento de falha)
        Integer contagemProjecao = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM financial_projection_venda WHERE venda_id = ?",
                Integer.class,
                vendaId
        );
        assertEquals(0, contagemProjecao, "A projeção não deve conter o registro da venda devido à falha.");

        Integer contagemCheckpoint = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM financial_projection_checkpoint WHERE lancamento_id = ?",
                Integer.class,
                lancamentoId
        );
        assertEquals(0, contagemCheckpoint, "O checkpoint não deve estar gravado devido ao rollback da transação.");

        // 4. Ativação da Auto-Cura: Restaura comportamento real do repositório e aciona o scheduler
        Mockito.reset(projectionRepository);

        projectionRetryScheduler.reprocessarFalhasDeProjecao();

        // 5. Critérios de Aceite Obrigatórios pós auto-cura
        // a) projection_retry_queue deve estar vazia (registro processado e removido)
        assertEquals(0, retryRepository.count(), "A fila de retry deve estar vazia após auto-cura bem sucedida.");

        // b) financial_projection_venda deve exibir saldo = 150.00, total_creditos = 150.00, total_estornos = 0.00
        Map<String, Object> projecao = jdbcTemplate.queryForMap(
                "SELECT saldo, total_creditos, total_estornos FROM financial_projection_venda WHERE venda_id = ?",
                vendaId
        );
        assertEquals(0, new BigDecimal("150.00").compareTo((BigDecimal) projecao.get("saldo")),
                "Saldo da projeção deve ser 150.00");
        assertEquals(0, new BigDecimal("150.00").compareTo((BigDecimal) projecao.get("total_creditos")),
                "Total de créditos da projeção deve ser 150.00");
        assertEquals(0, new BigDecimal("0.00").compareTo((BigDecimal) projecao.get("total_estornos")),
                "Total de estornos da projeção deve ser 0.00");

        // c) financial_projection_checkpoint deve conter o lancamento_id persistido
        Integer checkpointFinal = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM financial_projection_checkpoint WHERE lancamento_id = ?",
                Integer.class,
                lancamentoId
        );
        assertEquals(1, checkpointFinal, "O checkpoint deve estar registrado no banco.");

        // 6. Idempotência: Executa o scheduler uma segunda vez e valida que nenhuma operação duplicada é realizada
        projectionRetryScheduler.reprocessarFalhasDeProjecao();

        // Garante que saldo permanece inalterado
        Map<String, Object> projecaoSegundaVez = jdbcTemplate.queryForMap(
                "SELECT saldo, total_creditos, total_estornos FROM financial_projection_venda WHERE venda_id = ?",
                vendaId
        );
        assertEquals(0, new BigDecimal("150.00").compareTo((BigDecimal) projecaoSegundaVez.get("saldo")),
                "Saldo deve continuar 150.00 sem duplicação.");
        assertEquals(0, new BigDecimal("150.00").compareTo((BigDecimal) projecaoSegundaVez.get("total_creditos")),
                "Total de créditos deve continuar 150.00.");

        // Também testa chamada manual direta repetida ao listener para confirmar o bloqueio de duplicate key no checkpoint
        projectionListener.processarComIdempotencia(event);

        Map<String, Object> projecaoTerceiraVez = jdbcTemplate.queryForMap(
                "SELECT saldo, total_creditos, total_estornos FROM financial_projection_venda WHERE venda_id = ?",
                vendaId
        );
        assertEquals(0, new BigDecimal("150.00").compareTo((BigDecimal) projecaoTerceiraVez.get("saldo")),
                "Mesmo com re-execução direta do evento, o checkpoint impede duplo incremento.");
    }
}
