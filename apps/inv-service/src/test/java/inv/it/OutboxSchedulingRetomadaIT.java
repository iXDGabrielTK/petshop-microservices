/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.it;

import inv.InvServiceApplication;
import inv.shared.config.DateTimeConfig;
import inv.shared.config.RabbitMQConfig;
import inv.shared.outbox.model.Outbox;
import inv.shared.outbox.repository.OutboxRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(
        classes = InvServiceApplication.class,
        properties = {
                "inv.scheduling.enabled=true",
                "outbox.scheduler.enabled=true",
                "outbox.scheduler.initial-delay-ms=100",
                "outbox.scheduler.fixed-delay-ms=200",
                "finance.projection.diaria.enabled=false",
                "finance.projection.retry.fixed-delay-ms=3600000",
                "finance.projection.diaria.catch-up.initial-delay-ms=3600000"
        })
@ContextConfiguration(initializers = OutboxSchedulingRetomadaIT.RestartDueRowInitializer.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxSchedulingRetomadaIT {

    private static final String RK_AUTO = "e2.sched.auto." + UUID.randomUUID();
    private static final String QUEUE_AUTO = "e2.sched.auto.queue." + UUID.randomUUID();
    private static final String RK_RESTART = "e2.sched.restart." + UUID.randomUUID();
    private static final String QUEUE_RESTART = "e2.sched.restart.queue." + UUID.randomUUID();

    private static volatile Long pendingRestartOutboxId;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3-management");

    static class RestartDueRowInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            Long id = pendingRestartOutboxId;
            if (id == null) {
                return;
            }
            LocalDateTime vencido = LocalDateTime.now(DateTimeConfig.ZONE_SAO_PAULO).minusSeconds(5);
            try (Connection conn = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 PreparedStatement stmt = conn.prepareStatement(
                         "UPDATE outbox SET next_attempt_at = ? WHERE id = ?")) {
                stmt.setObject(1, vencido);
                stmt.setLong(2, id);
                int updated = stmt.executeUpdate();
                if (updated != 1) {
                    throw new IllegalStateException(
                            "Esperava atualizar 1 linha para retomada da outbox, atualizou " + updated);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Falha ao atualizar next_attempt_at antes da retomada do contexto", e);
            }
        }
    }

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
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @AfterAll
    static void limpar(@Autowired AmqpAdmin admin) {
        if (admin != null) {
            admin.deleteQueue(QUEUE_AUTO);
            admin.deleteQueue(QUEUE_RESTART);
        }
        pendingRestartOutboxId = null;
    }

    @Test
    @Order(1)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    @DisplayName("Cenário 2b (parte 1) — @EnableScheduling ativo drena registro imediato e preserva registro agendado para o futuro")
    void parte1_schedulerAtivoEntregaAutomaticamenteEPreservaRegistroFuturo() {
        jdbcTemplate.update("DELETE FROM outbox");

        amqpAdmin.declareQueue(new Queue(QUEUE_AUTO, true, false, false));
        amqpAdmin.declareBinding(BindingBuilder
                .bind(new Queue(QUEUE_AUTO, true, false, false))
                .to(new TopicExchange(RabbitMQConfig.PDV_EXCHANGE))
                .with(RK_AUTO));

        amqpAdmin.declareQueue(new Queue(QUEUE_RESTART, true, false, false));
        amqpAdmin.declareBinding(BindingBuilder
                .bind(new Queue(QUEUE_RESTART, true, false, false))
                .to(new TopicExchange(RabbitMQConfig.PDV_EXCHANGE))
                .with(RK_RESTART));

        LocalDateTime agora = LocalDateTime.now(clock);

        Outbox imediato = new Outbox();
        imediato.setExchange(RabbitMQConfig.PDV_EXCHANGE);
        imediato.setRoutingKey(RK_AUTO);
        imediato.setEventType("java.util.HashMap");
        imediato.setPayload("{\"origem\":\"scheduler-ativo\"}");
        imediato.setVersion(1);
        imediato.setCreatedAt(agora);
        Long imediatoId = outboxRepository.saveAndFlush(imediato).getId();

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(outboxRepository.findById(imediatoId)).isEmpty();
            Message entregue = rabbitTemplate.receive(QUEUE_AUTO, 1000);
            assertThat(entregue).isNotNull();
            assertThat(new String(entregue.getBody(), StandardCharsets.UTF_8))
                    .isEqualTo("{\"origem\":\"scheduler-ativo\"}");
        });

        Outbox futuro = new Outbox();
        futuro.setExchange(RabbitMQConfig.PDV_EXCHANGE);
        futuro.setRoutingKey(RK_RESTART);
        futuro.setEventType("java.util.HashMap");
        futuro.setPayload("{\"origem\":\"retomada-pos-reinicio\"}");
        futuro.setVersion(1);
        futuro.setCreatedAt(agora);
        futuro.setNextAttemptAt(agora.plusHours(1));
        pendingRestartOutboxId = outboxRepository.saveAndFlush(futuro).getId();

        assertThat(outboxRepository.findById(pendingRestartOutboxId)).isPresent();
    }

    @Test
    @Order(2)
    @DisplayName("Cenário 2b (parte 2) — Após reinício do contexto Spring, linha vencida é selecionada e entregue ao broker real")
    void parte2_aposReinicioDoContextoLinhaVencidaEEntregueAutomaticamente() {
        Long idRetomada = pendingRestartOutboxId;
        assertThat(idRetomada).isNotNull();

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(outboxRepository.findById(idRetomada)).isEmpty());

        Message recebida = rabbitTemplate.receive(QUEUE_RESTART, 5000);
        assertThat(recebida).isNotNull();
        assertThat(new String(recebida.getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"origem\":\"retomada-pos-reinicio\"}");
    }
}
