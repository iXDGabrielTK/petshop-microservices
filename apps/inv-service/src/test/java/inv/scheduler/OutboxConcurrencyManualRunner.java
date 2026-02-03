package inv.scheduler;

import inv.model.Outbox;
import inv.repository.OutboxRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Teste manual de concorrência.
 * Requer o container 'postgres-test' rodando na porta 5435.
 * Configure a variável de ambiente DOCKER_READY=true para rodar.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5435/testdb",
        "spring.datasource.username=usuario",
        "spring.datasource.password=senha",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "logging.level.root=INFO"
})
@EnabledIfEnvironmentVariable(named = "DOCKER_READY", matches = "true")
class OutboxConcurrencyManualRunner {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxProcessor outboxProcessor;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder; // Necessário para subir o contexto de segurança

    @BeforeEach
    void setup() {
        outboxRepository.deleteAll();
    }

    @Test
    @DisplayName("Concorrência: Deve processar cada mensagem exatamente uma vez com múltiplas threads")
    void deveProcessarComConcorrenciaSemErros() throws InterruptedException {
        // 1. SETUP: Inserir carga no banco
        int totalMensagens = 100;
        List<Outbox> mensagens = new ArrayList<>();

        for (int i = 0; i < totalMensagens; i++) {
            Outbox outbox = new Outbox();
            // Usando HashMap pois é uma classe padrão do Java que o ObjectMapper consegue desserializar de "{}"
            outbox.setEventType("java.util.HashMap");
            outbox.setPayload("{}");
            outbox.setExchange("ex.teste");
            outbox.setRoutingKey("rk.teste");
            outbox.setCreatedAt(LocalDateTime.now());
            mensagens.add(outbox);
        }
        outboxRepository.saveAll(mensagens);

        System.out.println("=== INÍCIO DO TESTE DE CONCORRÊNCIA ===");

        // 2. AÇÃO: Executar processamento em paralelo
        int numeroDeThreads = 5;
        AtomicInteger totalProcessadoSucesso = new AtomicInteger(0);
        List<Exception> erros = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newFixedThreadPool(numeroDeThreads)) {
            CountDownLatch largada = new CountDownLatch(1);
            CountDownLatch chegada = new CountDownLatch(numeroDeThreads);

            for (int i = 0; i < numeroDeThreads; i++) {
                executor.submit(() -> {
                    try {
                        largada.await(); // Espera sinal para começar tudo junto

                        boolean temMais = true;
                        while (temMais) {
                            try {
                                temMais = outboxProcessor.processNext();
                                if (temMais) {
                                    totalProcessadoSucesso.incrementAndGet();
                                }
                            } catch (Exception e) {
                                erros.add(e);
                                temMais = false;
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        chegada.countDown();
                    }
                });
            }

            largada.countDown(); // Sinaliza para todas as threads começarem
            boolean terminou = chegada.await(20, TimeUnit.SECONDS);

            // 3. VALIDAÇÕES
            System.out.println("Total processado: " + totalProcessadoSucesso.get());
            System.out.println("Erros: " + erros);

            Assertions.assertTrue(terminou, "O teste demorou demais e sofreu timeout");
            Assertions.assertTrue(erros.isEmpty(), "Ocorreram exceções durante o processamento: " + erros);

            // Verifica consistência: Nada no banco, tudo processado
            Assertions.assertEquals(0, outboxRepository.count(), "Ainda existem registros no banco (SKIP LOCKED falhou?)");
            Assertions.assertEquals(totalMensagens, totalProcessadoSucesso.get(), "Número de mensagens processadas diverge do inserido");

            // Verifica se o RabbitMQ foi chamado o número correto de vezes
            Mockito.verify(rabbitTemplate, Mockito.times(totalMensagens))
                    .convertAndSend(Mockito.anyString(), Mockito.anyString(), Mockito.any(Object.class));
        }
    }
}