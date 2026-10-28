package mail.service;

import mail.config.RabbitMQConfig;
import mail.message.PasswordResetMessage;
import mail.repository.ProcessedEventRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Teste de concorrência e idempotência conforme especificação do ADR-0008 (Seção 6).
 * Valida o padrão Distributed Idempotent Consumer com tabela de deduplicação (ProcessedEvent),
 * garantindo que mesmo com 10 entregas duplicadas do broker, o e-mail é enviado exatamente 1 vez
 * e todas as 9 redundâncias sofrem ACK sem efeitos colaterais.
 */
@Testcontainers
@SpringBootTest
class EmailConsumerIdempotenciaIntegrationTest {

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
    @DisplayName("ADR-0008 Seção 6: 10 mensagens com o mesmo eventId devem resultar em exatamente 1 envio de email e 1 registro de deduplicação")
    void deveGarantirIdempotenciaCom10MensagensDuplicadas() {
        String eventId = "uuid-teste-dedup-123";
        int totalMensagens = 10;

        PasswordResetMessage mensagem = new PasswordResetMessage(
                1,
                eventId,
                "cliente@petshop.local",
                "token-secreto-reset-123",
                "Gabriel"
        );

        // Dispara 10 publicações idênticas no broker
        for (int i = 0; i < totalMensagens; i++) {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGER_NAME,
                    RabbitMQConfig.ROUTING_KEY,
                    mensagem
            );
        }

        // Aguarda o processamento das mensagens assíncronas pelo listener RabbitMQ
        await()
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    // 1. O envio do e-mail deve ter sido invocado exatamente 1 vez
                    Mockito.verify(mailSender, Mockito.times(1)).send(Mockito.any(SimpleMailMessage.class));

                    // 2. A tabela processed_events deve ter exatamente 1 linha
                    assertEquals(1, processedEventRepository.count(), "A tabela processed_events deve conter exatamente 1 registro.");
                    assertTrue(processedEventRepository.existsByEventId(eventId), "O eventId deve estar registrado na tabela de deduplicação.");
                });

        // Aguarda estabilização para garantir que as 9 duplicatas foram consumidas (ACKed)
        await()
                .during(Duration.ofMillis(1500))
                .atMost(Duration.ofSeconds(4))
                .untilAsserted(() -> {
                    // Confirmação que não houve novas chamadas ao mailSender
                    Mockito.verify(mailSender, Mockito.times(1)).send(Mockito.any(SimpleMailMessage.class));
                    assertEquals(1, processedEventRepository.count(), "A contagem de eventos processados deve permanecer 1.");
                });
    }
}
