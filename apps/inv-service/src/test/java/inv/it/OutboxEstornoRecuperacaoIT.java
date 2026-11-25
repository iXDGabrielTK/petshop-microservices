/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.it;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import common.security.RsaKeyUtils;
import inv.InvServiceApplication;
import inv.checkout.domain.model.StatusVenda;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.checkout.infrastructure.web.dto.ItemVendaRequest;
import inv.checkout.infrastructure.web.dto.VendaRequest;
import inv.checkout.usecase.PagamentoService;
import inv.checkout.usecase.VendaService;
import inv.finance.domain.model.LancamentoFinanceiro;
import inv.finance.domain.model.TipoLancamento;
import inv.finance.infrastructure.messaging.VendaEstornadaEventListener;
import inv.finance.infrastructure.persistence.LancamentoFinanceiroRepository;
import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.UnidadeMedida;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import inv.shared.config.DateTimeConfig;
import inv.shared.config.RabbitMQConfig;
import inv.shared.domain.MetodoPagamento;
import inv.shared.event.VendaEstornadaEvent;
import inv.shared.outbox.metrics.OutboxMetrics;
import inv.shared.outbox.model.Outbox;
import inv.shared.outbox.repository.OutboxRepository;
import inv.shared.outbox.scheduler.OutboxProcessor;
import inv.shared.outbox.scheduler.OutboxStepResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(
        classes = InvServiceApplication.class,
        properties = {
                "inv.scheduling.enabled=false",
                "features.cancelamento-venda-concluida.enabled=true",
                "finance.projection.diaria.enabled=true",
                "outbox.retry.max-attempts=3",
                "outbox.retry.base-delay-ms=2000",
                "outbox.retry.max-delay-ms=60000"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxEstornoRecuperacaoIT {

    private static final Instant BASE_INSTANT = Instant.parse("2026-11-24T15:00:00Z");
    private static final KeyPair KEY_PAIR = RsaKeyUtils.generateKeyPair();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3-management");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);

        String pubKey = Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded());
        registry.add("jwt.public.key", () -> pubKey);
    }

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private Clock clock;

    @MockitoSpyBean
    private OutboxRepository outboxRepository;

    @MockitoSpyBean
    private RabbitTemplate rabbitTemplate;

    @MockitoSpyBean
    private VendaEstornadaEventListener vendaEstornadaEventListener;

    @Autowired
    private OutboxProcessor outboxProcessor;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private RabbitListenerEndpointRegistry listeners;

    @Autowired
    private VendaService vendaService;

    @Autowired
    private PagamentoService pagamentoService;

    @Autowired
    private VendaRepository vendaRepository;

    @Autowired
    private ProdutoRepository produtoRepository;

    @Autowired
    private LancamentoFinanceiroRepository lancamentoRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;
    private final AtomicReference<Instant> currentInstant = new AtomicReference<>(BASE_INSTANT);

    @BeforeEach
    void preparar() {
        currentInstant.set(BASE_INSTANT);
        Mockito.reset(outboxRepository, rabbitTemplate, clock);
        Mockito.clearInvocations(vendaEstornadaEventListener);
        when(clock.getZone()).thenReturn(DateTimeConfig.ZONE_SAO_PAULO);
        when(clock.instant()).thenAnswer(invocation -> currentInstant.get());

        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter((Filter) context.getBean("springSecurityFilterChain"))
                .build();

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

        amqpAdmin.purgeQueue(RabbitMQConfig.QUEUE_PAGAMENTO_CONFIRMADO);
        amqpAdmin.purgeQueue(RabbitMQConfig.DLQ_QUEUE_PAGAMENTO_CONFIRMADO);
        amqpAdmin.purgeQueue(RabbitMQConfig.QUEUE_VENDA_ESTORNADA);
        amqpAdmin.purgeQueue(RabbitMQConfig.QUEUE_VENDA_ESTORNADA_DLQ);
        listeners.start();
    }

    @AfterAll
    static void tearDownAll(@Autowired RabbitListenerEndpointRegistry registry) {
        if (registry != null) {
            registry.stop();
        }
    }

    @Test
    @DisplayName("Cenário 1 — Recuperação pós-confirmação AMQP / pré-commit mantém 1 único ESTORNO e projeções conciliadas")
    void cenario1_recuperacaoPosConfirmacaoAmqpPreCommitComEstornoUnico() throws Exception {
        Long vendaId = criarVendaConcluidaEDrenarPagamento("7890006000011", "NSU-E3-C1");
        String adminAuth = "Bearer " + criarToken("admin@petshop.com", List.of("ROLE_ADMIN"));

        LocalDateTime tCancelamento = LocalDateTime.now(clock);
        mockMvc.perform(post("/vendas/{id}/cancelar", vendaId)
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(vendaId))
                .andExpect(jsonPath("$.status").value("CANCELADA"))
                .andExpect(jsonPath("$.valorTotal").value(100.00))
                .andExpect(jsonPath("$.valorPago").value(120.00))
                .andExpect(jsonPath("$.valorTroco").value(20.00));

        List<Outbox> pendentes = outboxRepository.findAll();
        assertThat(pendentes).hasSize(1);
        Outbox eventoEstorno = pendentes.getFirst();
        assertThat(eventoEstorno.getRoutingKey()).isEqualTo(RabbitMQConfig.ROUTING_KEY_VENDA_ESTORNADA);
        assertThat(eventoEstorno.getEventType()).isEqualTo(VendaEstornadaEvent.class.getName());
        assertThat(eventoEstorno.getVersion()).isEqualTo(1);
        assertThat(eventoEstorno.getCreatedAt()).isEqualTo(tCancelamento);
        assertThat(lancamentoRepository.findAll().stream()
                .filter(l -> l.getVendaId().equals(vendaId) && l.getTipo() == TipoLancamento.ESTORNO)
                .toList()).isEmpty();

        doThrow(new DataAccessResourceFailureException("Falha simulada pós-confirm antes do commit de exclusão"))
                .when(outboxRepository).delete(any(Outbox.class));

        try {
            // Tentativa 1: entrega real no RabbitMQ com Confirm(ack=true), mas delete() falha antes do commit
            assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.FAILED_PAUSE);
        } finally {
            Mockito.reset(outboxRepository);
        }

        Outbox aposRollback = outboxRepository.findById(eventoEstorno.getId()).orElseThrow();
        assertThat(aposRollback.getStatus()).isEqualTo(Outbox.STATUS_PENDING);
        assertThat(aposRollback.getAttempts()).isEqualTo(1);
        assertThat(aposRollback.getLastErrorType()).isEqualTo("POST_CONFIRM_PERSISTENCE_ERROR");
        assertThat(aposRollback.getNextAttemptAt()).isEqualTo(tCancelamento.plusSeconds(2));
        assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.EMPTY);

        UUID uuidEstornoEsperado = UUID.nameUUIDFromBytes(("ESTORNO:" + vendaId).getBytes(StandardCharsets.UTF_8));
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(vendaEstornadaEventListener, times(1)).handleVendaEstornada(any());
            List<LancamentoFinanceiro> estornos = lancamentoRepository.findAll().stream()
                    .filter(l -> l.getVendaId().equals(vendaId) && l.getTipo() == TipoLancamento.ESTORNO)
                    .toList();
            assertThat(estornos).hasSize(1);
            assertThat(estornos.getFirst().getValor()).isEqualByComparingTo("-100.00");
            assertThat(estornos.getFirst().getReferencia()).isEqualTo(uuidEstornoEsperado);
            assertProjecoesConciliadas(vendaId);
        });

        // Tentativa 2 após backoff via broker real com o mesmo uuidReferenciaEstorno
        avancarRelogio(Duration.ofSeconds(2));
        assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.DELIVERED);
        assertThat(outboxRepository.count()).isZero();

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                verify(vendaEstornadaEventListener, times(2)).handleVendaEstornada(any()));

        List<LancamentoFinanceiro> estornosFinais = lancamentoRepository.findAll().stream()
                .filter(l -> l.getVendaId().equals(vendaId) && l.getTipo() == TipoLancamento.ESTORNO)
                .toList();
        assertThat(estornosFinais).hasSize(1);
        assertThat(estornosFinais.getFirst().getValor()).isEqualByComparingTo("-100.00");
        assertThat(estornosFinais.getFirst().getReferencia()).isEqualTo(uuidEstornoEsperado);
        assertProjecoesConciliadas(vendaId);

        assertThat(rabbitTemplate.receive(RabbitMQConfig.QUEUE_VENDA_ESTORNADA, 500)).isNull();
        assertThat(rabbitTemplate.receive(RabbitMQConfig.QUEUE_VENDA_ESTORNADA_DLQ, 500)).isNull();
        assertThat(rabbitTemplate.receive(RabbitMQConfig.QUEUE_PAGAMENTO_CONFIRMADO, 500)).isNull();
        assertThat(rabbitTemplate.receive(RabbitMQConfig.DLQ_QUEUE_PAGAMENTO_CONFIRMADO, 500)).isNull();
    }

    @Test
    @DisplayName("Cenário 2 — Contraste com falha antes do envio AMQP: zero entregas na falha e 1 estorno após retomada")
    void cenario2_contrasteFalhaAntesDoEnvioAmqp() throws Exception {
        Long vendaId = criarVendaConcluidaEDrenarPagamento("7890006000028", "NSU-E3-C2");
        String adminAuth = "Bearer " + criarToken("admin@petshop.com", List.of("ROLE_ADMIN"));

        LocalDateTime tCancelamento = LocalDateTime.now(clock);
        mockMvc.perform(post("/vendas/{id}/cancelar", vendaId)
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELADA"));

        Outbox eventoEstorno = outboxRepository.findAll().stream().findFirst().orElseThrow();

        doThrow(new AmqpConnectException(new RuntimeException("Broker indisponível antes do envio")))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        try {
            assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.FAILED_PAUSE);

            Outbox preservado = outboxRepository.findById(eventoEstorno.getId()).orElseThrow();
            assertThat(preservado.getStatus()).isEqualTo(Outbox.STATUS_PENDING);
            assertThat(preservado.getAttempts()).isEqualTo(1);
            assertThat(preservado.getLastErrorType()).isEqualTo("AMQP_CONNECT_ERROR");
            assertThat(preservado.getNextAttemptAt()).isEqualTo(tCancelamento.plusSeconds(2));

            verify(vendaEstornadaEventListener, never()).handleVendaEstornada(any());
            assertThat(lancamentoRepository.findAll().stream()
                    .filter(l -> l.getVendaId().equals(vendaId) && l.getTipo() == TipoLancamento.ESTORNO)
                    .toList()).isEmpty();
        } finally {
            Mockito.reset(rabbitTemplate);
        }

        avancarRelogio(Duration.ofSeconds(2));
        assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.DELIVERED);
        assertThat(outboxRepository.count()).isZero();

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(vendaEstornadaEventListener, times(1)).handleVendaEstornada(any());
            List<LancamentoFinanceiro> estornos = lancamentoRepository.findAll().stream()
                    .filter(l -> l.getVendaId().equals(vendaId) && l.getTipo() == TipoLancamento.ESTORNO)
                    .toList();
            assertThat(estornos).hasSize(1);
            assertThat(estornos.getFirst().getValor()).isEqualByComparingTo("-100.00");
            assertProjecoesConciliadas(vendaId);
        });

        assertThat(rabbitTemplate.receive(RabbitMQConfig.QUEUE_VENDA_ESTORNADA, 500)).isNull();
        assertThat(rabbitTemplate.receive(RabbitMQConfig.QUEUE_VENDA_ESTORNADA_DLQ, 500)).isNull();
    }

    @Test
    @DisplayName("Cenário 4 — Métricas Micrometer (/actuator/prometheus), quarentena e log outbox_replay_completed")
    void cenario4_metricasPrometheusQuarentenaEReplayLog() throws Exception {
        String adminAuth = "Bearer " + criarToken("admin@petshop.com", List.of("ROLE_ADMIN"));

        // (a) Idade do registro elegível mais antigo, Clock atrasado e tolerância a falha transitória de banco
        Gauge idadeGauge = meterRegistry.find(OutboxMetrics.METRIC_OLDEST_ELIGIBLE_AGE_SECONDS).gauge();
        assertThat(idadeGauge).isNotNull();
        assertThat(idadeGauge.value()).isZero();

        LocalDateTime t0 = LocalDateTime.now(clock);
        Outbox elegivel = criarOutbox(
                RabbitMQConfig.PDV_EXCHANGE,
                "e3.c4.idade." + UUID.randomUUID(),
                inv.checkout.infrastructure.messaging.event.PagamentoConfirmadoEvent.class.getName(),
                "{\"vendaId\":1,\"pagamentoId\":1}",
                t0);
        Long idIdade = outboxRepository.saveAndFlush(elegivel).getId();

        avancarRelogio(Duration.ofSeconds(90));
        assertThat(idadeGauge.value()).isEqualTo(90.0d);

        MvcResult promOk = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(promOk.getResponse().getContentAsString())
                .contains("outbox_oldest_eligible_age_seconds 90.0");

        // Clock atrás do created_at nunca pode gerar idade negativa
        jdbcTemplate.update("UPDATE outbox SET created_at = ? WHERE id = ?",
                LocalDateTime.now(clock).plusSeconds(120), idIdade);
        assertThat(idadeGauge.value()).isZero();

        // Falha transitória de banco não pode degradar /actuator/prometheus
        doThrow(new DataAccessResourceFailureException("Erro transitório simulado"))
                .when(outboxRepository).findOldestEligibleAgeSeconds(any());
        doThrow(new DataAccessResourceFailureException("Erro transitório simulado"))
                .when(outboxRepository).countByStatus(anyString());
        try {
            MvcResult promResiliente = mockMvc.perform(get("/actuator/prometheus"))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(promResiliente.getResponse().getContentAsString())
                    .contains("outbox_oldest_eligible_age_seconds")
                    .contains("outbox_quarantined_count");
        } finally {
            Mockito.reset(outboxRepository);
        }
        outboxRepository.deleteById(idIdade);

        // (b) Registro movido para quarentena e listado via API administrativa
        double quarentenaInicial = lerCounter(OutboxMetrics.METRIC_QUARANTINED_TOTAL, "NO_ROUTE");
        String rkReplay = "e3.c4.replay." + UUID.randomUUID();
        String filaReplay = "e3.c4.queue." + UUID.randomUUID();
        String eventTypePermitido = inv.checkout.infrastructure.messaging.event.PagamentoConfirmadoEvent.class.getName();
        Long idQuarentena = outboxRepository.saveAndFlush(criarOutbox(
                RabbitMQConfig.PDV_EXCHANGE,
                rkReplay,
                eventTypePermitido,
                "{\"vendaId\":77,\"pagamentoId\":88}",
                LocalDateTime.now(clock))).getId();

        assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.FAILED_CONTINUE);
        avancarRelogio(Duration.ofSeconds(2));
        assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.FAILED_CONTINUE);
        avancarRelogio(Duration.ofSeconds(4));
        assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.FAILED_CONTINUE);

        Gauge quarantinedGauge = meterRegistry.find(OutboxMetrics.METRIC_QUARANTINED_COUNT).gauge();
        assertThat(quarantinedGauge).isNotNull();
        assertThat(quarantinedGauge.value()).isEqualTo(1.0d);
        assertThat(lerCounter(OutboxMetrics.METRIC_QUARANTINED_TOTAL, "NO_ROUTE"))
                .isEqualTo(quarentenaInicial + 1.0d);

        mockMvc.perform(get("/admin/outbox/quarantined").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(idQuarentena))
                .andExpect(jsonPath("$.content[0].status").value(Outbox.STATUS_QUARANTINED))
                .andExpect(jsonPath("$.content[0].lastErrorType").value("NO_ROUTE"));

        // (c) Replay administrativo, entrega pelo worker, log estruturado e 404 após drenagem
        double replayReqInicial = lerCounter(OutboxMetrics.METRIC_REPLAY_REQUESTED_TOTAL);
        double replayCompInicial = lerCounter(OutboxMetrics.METRIC_REPLAY_COMPLETED_TOTAL);

        Logger processorLogger = (Logger) LoggerFactory.getLogger(OutboxProcessor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        processorLogger.addAppender(appender);

        try {
            amqpAdmin.declareQueue(new Queue(filaReplay, true, false, false));
            amqpAdmin.declareBinding(BindingBuilder
                    .bind(new Queue(filaReplay, true, false, false))
                    .to(new TopicExchange(RabbitMQConfig.PDV_EXCHANGE))
                    .with(rkReplay));

            mockMvc.perform(post("/admin/outbox/{id}/replay", idQuarentena)
                            .header("Authorization", adminAuth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(idQuarentena))
                    .andExpect(jsonPath("$.status").value(Outbox.STATUS_PENDING))
                    .andExpect(jsonPath("$.replayCount").value(1));

            assertThat(lerCounter(OutboxMetrics.METRIC_REPLAY_REQUESTED_TOTAL))
                    .isEqualTo(replayReqInicial + 1.0d);
            assertThat(quarantinedGauge.value()).isZero();

            assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.DELIVERED);
            assertThat(lerCounter(OutboxMetrics.METRIC_REPLAY_COMPLETED_TOTAL))
                    .isEqualTo(replayCompInicial + 1.0d);

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(msg -> assertThat(msg)
                            .contains("event=outbox_replay_completed")
                            .contains("outboxId=" + idQuarentena)
                            .contains("eventType=" + eventTypePermitido)
                            .contains("version=1")
                            .contains("replayCount=1")
                            .contains("messageId=outbox-" + idQuarentena)
                            .contains("correlationId=outbox-" + idQuarentena + "-"));

            Message entregue = rabbitTemplate.receive(filaReplay, 5000);
            assertThat(entregue).isNotNull();

            mockMvc.perform(get("/admin/outbox/{id}", idQuarentena).header("Authorization", adminAuth))
                    .andExpect(status().isNotFound());

            MvcResult promFinal = mockMvc.perform(get("/actuator/prometheus"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("outbox_replay_requested_total")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("outbox_replay_completed_total")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("outbox_quarantined_total")))
                    .andReturn();
            assertThat(promFinal.getResponse().getContentAsString())
                    .contains("outbox_quarantined_count 0.0");
        } finally {
            processorLogger.detachAppender(appender);
            amqpAdmin.deleteQueue(filaReplay);
        }
    }

    private Long criarVendaConcluidaEDrenarPagamento(String codigoBarras, String nsu) {
        Produto produto = new Produto();
        produto.setCodigoBarras(codigoBarras);
        produto.setNome("Produto Estorno E3");
        produto.setUnidadeMedida(UnidadeMedida.UN);
        produto.setPrecoVenda(new BigDecimal("100.00"));
        produto.setEstoqueDisponivel(new BigDecimal("10.000"));
        produto.setEstoqueReservado(BigDecimal.ZERO);
        produto.setEstoqueMinimo(new BigDecimal("2.000"));
        Long produtoId = produtoRepository.saveAndFlush(produto).getId();

        Venda venda = vendaService.iniciarVenda(new VendaRequest(List.of(
                new ItemVendaRequest(produtoId, new BigDecimal("1.000"))
        )));
        Long vendaId = venda.getId();

        Long pagamentoId = pagamentoService.criarIntencaoPagamento(
                vendaId, MetodoPagamento.DINHEIRO, new BigDecimal("120.00"), UUID.randomUUID());
        pagamentoService.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("120.00"), nsu);

        List<Outbox> pagamentoOutbox = outboxRepository.findAll();
        assertThat(pagamentoOutbox).hasSize(1);
        assertThat(pagamentoOutbox.getFirst().getCreatedAt()).isEqualTo(LocalDateTime.now(clock));

        assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.DELIVERED);
        assertThat(outboxRepository.count()).isZero();

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(vendaRepository.findById(vendaId).orElseThrow().getStatus())
                    .isEqualTo(StatusVenda.CONCLUIDA);
            BigDecimal saldo = jdbcTemplate.queryForObject(
                    "SELECT saldo FROM financial_projection_venda WHERE venda_id = ?",
                    BigDecimal.class, vendaId);
            BigDecimal totalCreditosDiario = jdbcTemplate.queryForObject(
                    "SELECT total_creditos FROM financial_projection_diaria WHERE data_referencia = CURRENT_DATE",
                    BigDecimal.class);
            assertThat(saldo).isEqualByComparingTo("100.00");
            assertThat(totalCreditosDiario).isEqualByComparingTo("100.00");
        });

        return vendaId;
    }

    private void assertProjecoesConciliadas(Long vendaId) {
        BigDecimal saldo = jdbcTemplate.queryForObject(
                "SELECT saldo FROM financial_projection_venda WHERE venda_id = ?",
                BigDecimal.class, vendaId);
        BigDecimal totalCreditos = jdbcTemplate.queryForObject(
                "SELECT total_creditos FROM financial_projection_venda WHERE venda_id = ?",
                BigDecimal.class, vendaId);
        BigDecimal totalEstornos = jdbcTemplate.queryForObject(
                "SELECT total_estornos FROM financial_projection_venda WHERE venda_id = ?",
                BigDecimal.class, vendaId);

        BigDecimal totalCreditosDiario = jdbcTemplate.queryForObject(
                "SELECT total_creditos FROM financial_projection_diaria WHERE data_referencia = CURRENT_DATE",
                BigDecimal.class);
        BigDecimal totalDebitosDiario = jdbcTemplate.queryForObject(
                "SELECT total_debitos FROM financial_projection_diaria WHERE data_referencia = CURRENT_DATE",
                BigDecimal.class);
        Integer qtdLancamentosDiario = jdbcTemplate.queryForObject(
                "SELECT qtd_lancamentos FROM financial_projection_diaria WHERE data_referencia = CURRENT_DATE",
                Integer.class);

        assertThat(saldo).isEqualByComparingTo("0.00");
        assertThat(totalCreditos).isEqualByComparingTo("100.00");
        assertThat(totalEstornos).isEqualByComparingTo("100.00");
        assertThat(totalCreditosDiario).isEqualByComparingTo("100.00");
        assertThat(totalDebitosDiario).isEqualByComparingTo("100.00");
        assertThat(qtdLancamentosDiario).isEqualTo(2);
    }

    private void avancarRelogio(Duration delta) {
        currentInstant.updateAndGet(instant -> instant.plus(delta));
    }

    private double lerCounter(String name) {
        Counter counter = meterRegistry.find(name).counter();
        return counter != null ? counter.count() : 0.0d;
    }

    private double lerCounter(String name, String errorType) {
        Counter counter = meterRegistry.find(name).tag("error_type", errorType).counter();
        return counter != null ? counter.count() : 0.0d;
    }

    private static Outbox criarOutbox(
            String exchange,
            String routingKey,
            String eventType,
            String payload,
            LocalDateTime createdAt) {
        Outbox outbox = new Outbox();
        outbox.setExchange(exchange);
        outbox.setRoutingKey(routingKey);
        outbox.setEventType(eventType);
        outbox.setPayload(payload);
        outbox.setVersion(1);
        outbox.setCreatedAt(createdAt);
        return outbox;
    }

    private static String criarToken(String subject, List<String> roles) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer("https://auth-service")
                .claim("roles", roles)
                .claim("scope", "read write")
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + Duration.ofHours(1).toMillis()))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) KEY_PAIR.getPrivate()));
        return jwt.serialize();
    }
}
