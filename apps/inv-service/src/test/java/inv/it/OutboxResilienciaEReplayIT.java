/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.it;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import common.security.RsaKeyUtils;
import inv.InvServiceApplication;
import inv.shared.config.DateTimeConfig;
import inv.shared.config.RabbitMQConfig;
import inv.shared.outbox.model.Outbox;
import inv.shared.outbox.repository.OutboxRepository;
import inv.shared.outbox.scheduler.OutboxProcessor;
import inv.shared.outbox.scheduler.OutboxScheduler;
import inv.shared.outbox.scheduler.OutboxStepResult;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
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

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(
        classes = InvServiceApplication.class,
        properties = {
                "inv.scheduling.enabled=false",
                "outbox.retry.max-attempts=3",
                "outbox.retry.base-delay-ms=2000",
                "outbox.retry.max-delay-ms=60000",
                "finance.projection.diaria.enabled=false"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxResilienciaEReplayIT {

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

    @Autowired
    private OutboxProcessor outboxProcessor;

    @Autowired
    private OutboxScheduler outboxScheduler;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;
    private final AtomicReference<Instant> currentInstant = new AtomicReference<>(BASE_INSTANT);

    @BeforeEach
    void preparar() {
        currentInstant.set(BASE_INSTANT);
        Mockito.reset(outboxRepository, rabbitTemplate, clock);
        when(clock.getZone()).thenReturn(DateTimeConfig.ZONE_SAO_PAULO);
        when(clock.instant()).thenAnswer(invocation -> currentInstant.get());
        jdbcTemplate.update("DELETE FROM outbox");

        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter((Filter) context.getBean("springSecurityFilterChain"))
                .build();
    }

    private void avancarRelogio(Duration delta) {
        currentInstant.updateAndGet(instant -> instant.plus(delta));
    }

    @Test
    @DisplayName("Cenário 1 — Evento inválido antigo vai para QUARANTINED sem bloquear evento válido seguinte")
    void cenario1_eventoInvalidoVaiParaQuarentenaENaoBloqueiaEventoValido() {
        String rkValida = "e2.c1.valida." + UUID.randomUUID();
        String filaValida = "e2.c1.queue." + UUID.randomUUID();
        LocalDateTime agora = LocalDateTime.now(clock);

        Outbox poison = criarOutbox(
                RabbitMQConfig.PDV_EXCHANGE,
                "e2.c1.poison",
                "inv.inexistente.PoisonEvent",
                "{\"id\":\"poison\"}",
                1,
                agora.minusMinutes(2));
        Outbox valido = criarOutbox(
                RabbitMQConfig.PDV_EXCHANGE,
                rkValida,
                inv.checkout.infrastructure.messaging.event.PagamentoConfirmadoEvent.class.getName(),
                "{\"vendaId\":1,\"pagamentoId\":1}",
                1,
                agora.minusMinutes(1));
        Long poisonId = outboxRepository.saveAndFlush(poison).getId();
        Long validoId = outboxRepository.saveAndFlush(valido).getId();

        try {
            amqpAdmin.declareQueue(new Queue(filaValida, true, false, false));
            amqpAdmin.declareBinding(BindingBuilder
                    .bind(new Queue(filaValida, true, false, false))
                    .to(new TopicExchange(RabbitMQConfig.PDV_EXCHANGE))
                    .with(rkValida));

            outboxScheduler.processOutbox();

            Outbox poisonAtualizado = outboxRepository.findById(poisonId).orElseThrow();
            assertThat(poisonAtualizado.getStatus()).isEqualTo(Outbox.STATUS_QUARANTINED);
            assertThat(poisonAtualizado.getPayload()).isEqualTo("{\"id\":\"poison\"}");
            assertThat(poisonAtualizado.getLastErrorType()).isEqualTo("UNKNOWN_EVENT_TYPE");
            assertThat(poisonAtualizado.getLastError()).isNotBlank();
            assertThat(poisonAtualizado.getQuarantinedAt()).isEqualTo(agora);
            assertThat(poisonAtualizado.getNextAttemptAt()).isNull();

            assertThat(outboxRepository.findById(validoId)).isEmpty();
            Message recebida = rabbitTemplate.receive(filaValida, 5000);
            assertThat(recebida).isNotNull();
            assertThat(new String(recebida.getBody(), StandardCharsets.UTF_8)).isEqualTo("{\"vendaId\":1,\"pagamentoId\":1}");

            assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.EMPTY);
        } finally {
            amqpAdmin.deleteQueue(filaValida);
        }
    }

    @Test
    @DisplayName("Cenário 2a — Backoff determinístico com Clock em broker fora e falha pós-confirm tratada como transitória")
    void cenario2a_backoffDeterministicoBrokerOfflineEFalhaPosConfirm() {
        LocalDateTime t0 = LocalDateTime.now(clock);
        Long idBrokerOff = outboxRepository.saveAndFlush(criarOutbox(
                RabbitMQConfig.PDV_EXCHANGE,
                "e2.c2a.offline",
                inv.checkout.infrastructure.messaging.event.PagamentoConfirmadoEvent.class.getName(),
                "{\"vendaId\":2,\"pagamentoId\":2}",
                1,
                t0.minusSeconds(10))).getId();

        doThrow(new AmqpConnectException(new RuntimeException("Broker offline simulado")))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        try {
            // Tentativa 1 (T0): pausa e agenda T0 + 2s
            assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.FAILED_PAUSE);
            Outbox apos1 = outboxRepository.findById(idBrokerOff).orElseThrow();
            assertThat(apos1.getStatus()).isEqualTo(Outbox.STATUS_PENDING);
            assertThat(apos1.getAttempts()).isEqualTo(1);
            assertThat(apos1.getNextAttemptAt()).isEqualTo(t0.plusSeconds(2));

            // Antes do vencimento do backoff (T0 + 1s): não elegível
            avancarRelogio(Duration.ofSeconds(1));
            assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.EMPTY);

            // Tentativa 2 (T0 + 2s): pausa e agenda +4s (T0 + 6s)
            avancarRelogio(Duration.ofSeconds(1));
            assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.FAILED_PAUSE);
            Outbox apos2 = outboxRepository.findById(idBrokerOff).orElseThrow();
            assertThat(apos2.getAttempts()).isEqualTo(2);
            assertThat(apos2.getNextAttemptAt()).isEqualTo(t0.plusSeconds(6));

            // Antes do 2º backoff (T0 + 5s): não elegível
            avancarRelogio(Duration.ofSeconds(3));
            assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.EMPTY);

            // Tentativa 3 (T0 + 6s = maxAttempts): move para QUARANTINED
            avancarRelogio(Duration.ofSeconds(1));
            assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.FAILED_PAUSE);
            Outbox apos3 = outboxRepository.findById(idBrokerOff).orElseThrow();
            assertThat(apos3.getStatus()).isEqualTo(Outbox.STATUS_QUARANTINED);
            assertThat(apos3.getAttempts()).isEqualTo(3);
            assertThat(apos3.getNextAttemptAt()).isNull();
            assertThat(apos3.getLastErrorType()).isEqualTo("AMQP_CONNECT_ERROR");
            assertThat(apos3.getQuarantinedAt()).isEqualTo(t0.plusSeconds(6));
        } finally {
            Mockito.reset(rabbitTemplate);
        }

        // Falha pós-confirm AMQP (erro ao excluir na outbox) deve ir para PENDING com backoff, nunca quarentena imediata
        String rkPosConfirm = "e2.c2a.posconfirm." + UUID.randomUUID();
        String filaPosConfirm = "e2.c2a.queue." + UUID.randomUUID();
        LocalDateTime t1 = LocalDateTime.now(clock);
        Long idPosConfirm = outboxRepository.saveAndFlush(criarOutbox(
                RabbitMQConfig.PDV_EXCHANGE,
                rkPosConfirm,
                inv.checkout.infrastructure.messaging.event.PagamentoConfirmadoEvent.class.getName(),
                "{\"vendaId\":3,\"pagamentoId\":3}",
                1,
                t1.minusSeconds(5))).getId();

        try {
            amqpAdmin.declareQueue(new Queue(filaPosConfirm, true, false, false));
            amqpAdmin.declareBinding(BindingBuilder
                    .bind(new Queue(filaPosConfirm, true, false, false))
                    .to(new TopicExchange(RabbitMQConfig.PDV_EXCHANGE))
                    .with(rkPosConfirm));

            doThrow(new DataAccessResourceFailureException("Erro simulado pós-confirm"))
                    .when(outboxRepository).delete(any(Outbox.class));
            try {
                assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.FAILED_PAUSE);
            } finally {
                Mockito.reset(outboxRepository);
            }

            Outbox aposFalhaPersistencia = outboxRepository.findById(idPosConfirm).orElseThrow();
            assertThat(aposFalhaPersistencia.getStatus()).isEqualTo(Outbox.STATUS_PENDING);
            assertThat(aposFalhaPersistencia.getAttempts()).isEqualTo(1);
            assertThat(aposFalhaPersistencia.getLastErrorType()).isEqualTo("POST_CONFIRM_PERSISTENCE_ERROR");
            assertThat(aposFalhaPersistencia.getNextAttemptAt()).isEqualTo(t1.plusSeconds(2));
            assertThat(rabbitTemplate.receive(filaPosConfirm, 5000)).isNotNull();

            avancarRelogio(Duration.ofSeconds(2));
            assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.DELIVERED);
            assertThat(outboxRepository.findById(idPosConfirm)).isEmpty();
            assertThat(rabbitTemplate.receive(filaPosConfirm, 5000)).isNotNull();
        } finally {
            Mockito.reset(outboxRepository);
            amqpAdmin.deleteQueue(filaPosConfirm);
        }
    }

    @Test
    @DisplayName("Cenário 3 — Quarentena por NO_ROUTE, inspeção admin, replay concorrente (200/409) e entrega única (version=0)")
    void cenario3_inspecaoEReplayConcorrentePreservandoVersaoZero() throws Exception {
        String rk = "e2.c3.replay." + UUID.randomUUID();
        String fila = "e2.c3.queue." + UUID.randomUUID();
        String payloadOriginal = "{\"vendaId\":77,\"pagamentoId\":88}";
        LocalDateTime t0 = LocalDateTime.now(clock);

        Long outboxId = outboxRepository.saveAndFlush(criarOutbox(
                RabbitMQConfig.PDV_EXCHANGE,
                rk,
                inv.checkout.infrastructure.messaging.event.PagamentoConfirmadoEvent.class.getName(),
                payloadOriginal,
                0,
                t0.minusSeconds(10))).getId();

        // Esgota 3 tentativas em NO_ROUTE avançando o Clock
        assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.FAILED_CONTINUE);
        avancarRelogio(Duration.ofSeconds(2));
        assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.FAILED_CONTINUE);
        avancarRelogio(Duration.ofSeconds(4));
        assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.FAILED_CONTINUE);

        Outbox quarentenado = outboxRepository.findById(outboxId).orElseThrow();
        assertThat(quarentenado.getStatus()).isEqualTo(Outbox.STATUS_QUARANTINED);
        assertThat(quarentenado.getVersion()).isZero();
        assertThat(quarentenado.getLastErrorType()).isEqualTo("NO_ROUTE");

        String adminAuth = "Bearer " + criarToken("admin@petshop.com", List.of("ROLE_ADMIN"));

        mockMvc.perform(get("/admin/outbox/quarantined").header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(outboxId))
                .andExpect(jsonPath("$.content[0].version").value(0))
                .andExpect(jsonPath("$.content[0].lastErrorType").value("NO_ROUTE"));

        mockMvc.perform(get("/admin/outbox/" + outboxId).header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(outboxId))
                .andExpect(jsonPath("$.payload").value(payloadOriginal))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.lastErrorType").value("NO_ROUTE"))
                .andExpect(jsonPath("$.lastError").isNotEmpty())
                .andExpect(jsonPath("$.quarantinedAt").isNotEmpty());

        try {
            amqpAdmin.declareQueue(new Queue(fila, true, false, false));
            amqpAdmin.declareBinding(BindingBuilder
                    .bind(new Queue(fila, true, false, false))
                    .to(new TopicExchange(RabbitMQConfig.PDV_EXCHANGE))
                    .with(rk));

            // Disparo concorrente de 2 requisições simultâneas para POST /admin/outbox/{id}/replay
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(2);
            List<MvcResult> resultados = Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < 2; i++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        MvcResult res = mockMvc.perform(post("/admin/outbox/" + outboxId + "/replay")
                                        .header("Authorization", adminAuth))
                                .andReturn();
                        resultados.add(res);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        doneGate.countDown();
                    }
                });
            }

            startGate.countDown();
            assertThat(doneGate.await(15, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            List<Integer> statuses = resultados.stream()
                    .map(r -> r.getResponse().getStatus())
                    .sorted()
                    .toList();
            assertThat(statuses).containsExactly(200, 409);

            MvcResult okResult = resultados.stream()
                    .filter(r -> r.getResponse().getStatus() == 200)
                    .findFirst()
                    .orElseThrow();
            assertThat(okResult.getResponse().getContentAsString()).contains("\"replayCount\":1");
            assertThat(okResult.getResponse().getContentAsString()).contains("\"status\":\"PENDING\"");

            assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.DELIVERED);
            assertThat(outboxRepository.findById(outboxId)).isEmpty();

            Message entregue = rabbitTemplate.receive(fila, 5000);
            assertThat(entregue).isNotNull();
            assertThat(new String(entregue.getBody(), StandardCharsets.UTF_8)).isEqualTo(payloadOriginal);
            assertThat(((Number) entregue.getMessageProperties().getHeader("x-event-version")).intValue()).isZero();
            assertThat(rabbitTemplate.receive(fila, 500)).isNull();

            mockMvc.perform(get("/admin/outbox/" + outboxId).header("Authorization", adminAuth))
                    .andExpect(status().isNotFound());
        } finally {
            amqpAdmin.deleteQueue(fila);
        }
    }

    @Test
    @DisplayName("Cenário 4 — Segurança /admin/outbox/**, teto size=100, 404 ProblemDetail e índices válidos na V11")
    void cenario4_autorizacaoTetoPaginacao404EIndicesValidos() throws Exception {
        String userAuth = "Bearer " + criarToken("user@petshop.com", List.of("ROLE_USER"));
        String adminAuth = "Bearer " + criarToken("admin@petshop.com", List.of("ROLE_ADMIN"));

        mockMvc.perform(get("/admin/outbox/quarantined"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/outbox/quarantined").header("Authorization", userAuth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Acesso Negado"));
        mockMvc.perform(post("/admin/outbox/1/replay").header("Authorization", userAuth))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin/outbox/quarantined")
                        .param("size", "250")
                        .header("Authorization", adminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));

        mockMvc.perform(post("/admin/outbox/999999/replay").header("Authorization", adminAuth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Recurso não encontrado"));

        List<Map<String, Object>> indices = jdbcTemplate.queryForList("""
                SELECT c.relname AS index_name, i.indisvalid
                  FROM pg_index i
                  JOIN pg_class c ON c.oid = i.indexrelid
                 WHERE c.relname IN ('idx_outbox_pending_created_at', 'idx_outbox_quarantined_at')
                 ORDER BY c.relname
                """);
        assertThat(indices).hasSize(2);
        assertThat(indices).allSatisfy(row -> assertThat(row.get("indisvalid")).isEqualTo(Boolean.TRUE));
    }

    private static Outbox criarOutbox(
            String exchange,
            String routingKey,
            String eventType,
            String payload,
            int version,
            LocalDateTime createdAt) {
        Outbox outbox = new Outbox();
        outbox.setExchange(exchange);
        outbox.setRoutingKey(routingKey);
        outbox.setEventType(eventType);
        outbox.setPayload(payload);
        outbox.setVersion(version);
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
