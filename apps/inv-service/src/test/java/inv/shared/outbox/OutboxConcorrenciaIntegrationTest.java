package inv.shared.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import inv.shared.outbox.model.Outbox;
import inv.shared.outbox.repository.OutboxRepository;
import inv.shared.outbox.scheduler.OutboxProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Teste de concorrência e integridade física conforme especificação do ADR-0004 (Seção 6).
 * Valida o padrão Transactional Outbox com FOR UPDATE SKIP LOCKED executado por 10 workers
 * concorrentes, assegurando drenagem completa de 100 mensagens, zero duplicidade e ausência de conflitos.
 */
@Testcontainers
@SpringBootTest
class OutboxConcorrenciaIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3-management");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "30");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxProcessor outboxProcessor;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String TEST_EXCHANGE = "adr0004.outbox.exchange";
    private static final String TEST_ROUTING_KEY = "adr0004.outbox.key";
    private static final String TEST_QUEUE = "adr0004.outbox.queue";

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();

        DirectExchange exchange = new DirectExchange(TEST_EXCHANGE, true, false);
        Queue queue = new Queue(TEST_QUEUE, true, false, false);
        amqpAdmin.declareExchange(exchange);
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(TEST_ROUTING_KEY));

        amqpAdmin.purgeQueue(TEST_QUEUE);
    }

    @Test
    @DisplayName("ADR-0004 Seção 6: 10 workers paralelos devem processar 100 mensagens com SKIP LOCKED com zero duplicação e drenagem total")
    void deveProcessarOutboxConcorrenteCom10WorkersSemDuplicacao() throws Exception {
        int totalMensagens = 100;
        int totalWorkers = 10;

        List<Outbox> outboxEntries = new ArrayList<>();
        for (int i = 0; i < totalMensagens; i++) {
            Outbox outbox = new Outbox();
            outbox.setEventType("java.util.HashMap");
            outbox.setPayload("{\"msgIndex\":" + i + ",\"uuid\":\"" + java.util.UUID.randomUUID() + "\"}");
            outbox.setExchange(TEST_EXCHANGE);
            outbox.setRoutingKey(TEST_ROUTING_KEY);
            outbox.setVersion(1);
            outbox.setCreatedAt(LocalDateTime.now().minusSeconds(totalMensagens - i));
            outboxEntries.add(outbox);
        }
        outboxRepository.saveAllAndFlush(outboxEntries);
        assertEquals(totalMensagens, outboxRepository.count(), "Deveriam existir 100 mensagens persistidas no outbox.");

        ExecutorService executor = Executors.newFixedThreadPool(totalWorkers);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(totalWorkers);

        AtomicInteger totalProcessadoSucesso = new AtomicInteger(0);
        List<Throwable> errosInesperados = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < totalWorkers; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    boolean temMais = true;
                    while (temMais) {
                        try {
                            temMais = outboxProcessor.processNext();
                            if (temMais) {
                                totalProcessadoSucesso.incrementAndGet();
                            }
                        } catch (Throwable t) {
                            errosInesperados.add(t);
                            temMais = false;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endGate.countDown();
                }
            });
        }

        // Disparo concorrente de todos os 10 workers simultaneamente
        startGate.countDown();

        boolean finalizado = endGate.await(45, TimeUnit.SECONDS);
        executor.shutdown();

        // 1. Ausência de timeout e ausência de erros transacionais / deadlocks
        assertTrue(finalizado, "O processamento concorrente demorou mais de 45 segundos para finalizar.");
        assertTrue(errosInesperados.isEmpty(), "Ocorreram exceções inesperadas durante o processamento: " + errosInesperados);

        // 2. Drenagem completa da tabela outbox
        assertEquals(totalMensagens, totalProcessadoSucesso.get(), "O somatório de mensagens processadas deve ser exatamente 100.");
        assertEquals(0, outboxRepository.count(), "A tabela outbox deve estar completamente vazia (0 registros).");

        // 3. Validação das mensagens entregues na fila do RabbitMQ: exatamente 100 mensagens e zero duplicações
        Set<Integer> indicesRecebidos = new HashSet<>();
        for (int i = 0; i < totalMensagens; i++) {
            Message message = rabbitTemplate.receive(TEST_QUEUE, 5000);
            assertNotNull(message, "Deveria haver mensagem disponível no RabbitMQ na iteração " + i);
            String jsonBody = new String(message.getBody(), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(jsonBody, Map.class);
            Integer msgIndex = (Integer) map.get("msgIndex");
            assertNotNull(msgIndex, "O campo msgIndex não deve ser nulo");
            boolean inserido = indicesRecebidos.add(msgIndex);
            assertTrue(inserido, "Mensagem duplicada detectada no RabbitMQ para msgIndex: " + msgIndex);
        }

        // 4. Confirmação de fila vazia (sem mensagens extras ou órfãs)
        Message residual = rabbitTemplate.receive(TEST_QUEUE, 500);
        assertNull(residual, "Fila RabbitMQ não deveria conter mensagens adicionais após drenagem de 100 itens.");
        assertEquals(totalMensagens, indicesRecebidos.size(), "Devem ter sido recebidas exatamente 100 mensagens únicas.");
    }
}
