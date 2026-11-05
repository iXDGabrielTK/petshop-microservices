package inv.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.finance.domain.model.ProjectionRetry;
import inv.finance.infrastructure.jobs.ProjectionRetryScheduler;
import inv.finance.infrastructure.messaging.FinancialProjectionListener;
import inv.finance.infrastructure.messaging.LancamentoRegistradoEvent;
import inv.finance.infrastructure.messaging.VendaEstornadaEventListener;
import inv.finance.infrastructure.messaging.VendaPagaEventListener;
import inv.finance.infrastructure.persistence.ProjecaoReceitaDiariaRepository;
import inv.finance.infrastructure.persistence.ProjectionRetryRepository;
import inv.shared.event.VendaEstornadaEvent;
import inv.shared.event.VendaPagaEvent;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "finance.projection.retry.fixed-delay-ms=3600000",
        "finance.projection.diaria.catch-up.initial-delay-ms=3600000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class ProjecaoDuravelSupport {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void banco(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean RabbitTemplate rabbitTemplate;
    @MockitoBean ConnectionFactory connectionFactory;
    @MockitoBean StringRedisTemplate redisTemplate;

    // Descarta a tarefa antes de executar o fast-path: nenhuma falha/retry pode ser registrada por ele.
    @MockitoBean(name = "projectionExecutor") Executor projectionExecutor;
    @MockitoSpyBean FinancialProjectionListener projectionListener;
    @MockitoSpyBean ProjectionRetryRepository retryRepository;
    @MockitoSpyBean ProjecaoReceitaDiariaRepository diariaRepository;
    @Autowired ProjectionRetryScheduler scheduler;
    @Autowired VendaPagaEventListener creditoListener;
    @Autowired VendaEstornadaEventListener estornoListener;
    @Autowired VendaRepository vendaRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;

    TransactionTemplate tx;
    long vendaId;

    @BeforeEach
    void prepararBanco() {
        tx = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM financial_projection_diaria_checkpoint");
        jdbc.update("DELETE FROM financial_projection_diaria");
        jdbc.update("DELETE FROM financial_projection_checkpoint_v2");
        jdbc.update("DELETE FROM financial_projection_venda");
        jdbc.update("DELETE FROM projection_retry_queue");
        jdbc.update("DELETE FROM lancamentos_financeiros");
        jdbc.update("DELETE FROM vendas");
        vendaId = vendaRepository.save(new Venda()).getId();
    }

    void registrarCreditoEEstorno() {
        tx.executeWithoutResult(status -> {
            creditoListener.onVendaPaga(new VendaPagaEvent(vendaId, UUID.randomUUID(), new BigDecimal("100.00")));
            estornoListener.handleVendaEstornada(new VendaEstornadaEvent(vendaId, UUID.randomUUID(), new BigDecimal("100.00")));
            assertThat(contar("lancamentos_financeiros")).isEqualTo(2);
            assertThat(contar("projection_retry_queue")).isEqualTo(2);
        });
    }

    LancamentoRegistradoEvent registrarCredito() throws Exception {
        creditoListener.onVendaPaga(new VendaPagaEvent(vendaId, UUID.randomUUID(), new BigDecimal("100.00")));
        return evento(retryRepository.findAll().getFirst());
    }

    LancamentoRegistradoEvent evento(ProjectionRetry retry) throws Exception {
        return mapper.readValue(retry.getPayload(), LancamentoRegistradoEvent.class);
    }

    void tornarElegivel() {
        jdbc.update("UPDATE projection_retry_queue SET proxima_execucao = now() - INTERVAL '1 minute'");
    }

    int contar(String tabela) {
        return jdbc.queryForObject("SELECT count(*) FROM " + tabela, Integer.class);
    }

    void conferirVenda(String saldo, String estornos) {
        var venda = jdbc.queryForMap("SELECT * FROM financial_projection_venda WHERE venda_id = ?", vendaId);
        assertThat((BigDecimal) venda.get("saldo")).isEqualByComparingTo(saldo);
        assertThat((BigDecimal) venda.get("total_creditos")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) venda.get("total_estornos")).isEqualByComparingTo(estornos);
    }
}
