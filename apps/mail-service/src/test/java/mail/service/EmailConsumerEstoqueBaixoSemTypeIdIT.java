/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package mail.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import mail.config.RabbitMQConfig;
import mail.message.EstoqueBaixoMessage;
import mail.repository.ProcessedEventRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessagePropertiesBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest
class EmailConsumerEstoqueBaixoSemTypeIdIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3-management");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");

        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        registry.add("management.health.mail.enabled", () -> "false");
    }

    @MockitoBean
    private JavaMailSender mailSender;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        processedEventRepository.deleteAll();
        Mockito.clearInvocations(mailSender);
    }

    @AfterAll
    static void tearDownAll(@Autowired RabbitListenerEndpointRegistry registry) {
        if (registry != null) {
            registry.stop();
        }
    }

    @Test
    @DisplayName("DEBT-ARCH-006 E1: Deve consumir alerta de estoque baixo JSON UTF-8 sem __TypeId__ por inferência de tipo")
    void deveConsumirAlertaEstoqueBaixoJsonSemTypeIdPorInferenciaDeTipo() throws Exception {
        String eventId = "evt-low-stock-" + UUID.randomUUID();
        EstoqueBaixoMessage payload = new EstoqueBaixoMessage(
                1,
                eventId,
                "Ração Teste Sem TypeId",
                new BigDecimal("2.500"),
                new BigDecimal("5.000"));
        String json = objectMapper.writeValueAsString(payload);

        MessageProperties props = MessagePropertiesBuilder.newInstance()
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId("outbox-101")
                .setCorrelationId("outbox-101-" + UUID.randomUUID())
                .setHeader("x-outbox-id", 101L)
                .setHeader("x-event-version", 1)
                .build();
        assertThat(props.getHeaders()).doesNotContainKey("__TypeId__");

        Message message = new Message(json.getBytes(StandardCharsets.UTF_8), props);
        rabbitTemplate.send(RabbitMQConfig.INVENTORY_EXCHANGE, RabbitMQConfig.LOW_STOCK_ROUTING_KEY, message);

        await()
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    ArgumentCaptor<SimpleMailMessage> emailCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
                    Mockito.verify(mailSender, Mockito.times(1)).send(emailCaptor.capture());

                    SimpleMailMessage sent = emailCaptor.getValue();
                    assertThat(sent.getSubject()).contains("Ração Teste Sem TypeId");
                    assertThat(sent.getText()).contains("Ração Teste Sem TypeId").contains("2.500");
                    assertThat(processedEventRepository.count()).isEqualTo(1L);
                    assertThat(processedEventRepository.existsByEventId(eventId)).isTrue();
                });
    }
}
