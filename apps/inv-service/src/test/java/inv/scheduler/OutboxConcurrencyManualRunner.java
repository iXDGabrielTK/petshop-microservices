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

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5435/testdb",
        "spring.datasource.username=usuario",
        "spring.datasource.password=senha",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect"
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
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setup() {
        outboxRepository.deleteAll();
    }

    @Test
    @DisplayName("Deve processar cada mensagem exatamente uma vez, mesmo com 5 threads concorrentes")
    void deveProcessarComConcorrenciaSemErros() throws InterruptedException {
        // 1. SETUP: Criar 100 mensagens
        int totalMensagens = 100;
        List<Outbox> mensagens = new ArrayList<>();

        for (int i = 0; i < totalMensagens; i++) {
            Outbox outbox = new Outbox();
            outbox.setEventType("java.util.HashMap");
            outbox.setPayload("{}");
            outbox.setExchange("ex.teste");
            outbox.setRoutingKey("rk.teste");
            outbox.setCreatedAt(LocalDateTime.now());
            mensagens.add(outbox);
        }
        outboxRepository.saveAll(mensagens);

        System.out.println("=== INÍCIO DO TESTE ===");
        System.out.println("Mensagens inseridas no banco: " + totalMensagens);

        // 2. AÇÃO: Rodar threads concorrentes
        int numeroDeThreads = 5;
        AtomicInteger totalProcessadoSucesso = new AtomicInteger(0);
        List<Exception> erros = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newFixedThreadPool(numeroDeThreads)) {
            CountDownLatch largada = new CountDownLatch(1);
            CountDownLatch chegada = new CountDownLatch(numeroDeThreads);

            System.out.println("Iniciando " + numeroDeThreads + " threads simultâneas...");

            for (int i = 0; i < numeroDeThreads; i++) {
                int threadId = i + 1;
                executor.submit(() -> {
                    try {
                        largada.await();
                        boolean temMais = true;
                        int processadosPorEssaThread = 0;

                        while (temMais) {
                            try {
                                temMais = outboxProcessor.processNext();
                                if (temMais) {
                                    totalProcessadoSucesso.incrementAndGet();
                                    processadosPorEssaThread++;
                                }
                            } catch (Exception e) {
                                erros.add(e);
                                temMais = false;
                            }
                        }
                        System.out.println("Thread " + threadId + " terminou. Processou: " + processadosPorEssaThread + " itens.");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        chegada.countDown();
                    }
                });
            }

            largada.countDown();
            boolean terminou = chegada.await(10, TimeUnit.SECONDS);

            // 3. RELATÓRIO FINAL
            System.out.println("\n=== RESULTADO FINAL ===");
            System.out.println("Tempo esgotou? " + !terminou);
            System.out.println("Erros capturados: " + erros.size());
            System.out.println("Total processado com sucesso (AtomicInteger): " + totalProcessadoSucesso.get());
            System.out.println("Restante no Banco de Dados: " + outboxRepository.count());
            System.out.println("=======================");

            // 4. VALIDAÇÕES (ASSERTS)
            Assertions.assertTrue(terminou, "O teste demorou demais");
            Assertions.assertTrue(erros.isEmpty(), "Erros encontrados: " + erros);

            // Valida se o banco zerou
            Assertions.assertEquals(0, outboxRepository.count(), "Erro: Ainda existem registros no banco!");

            // Valida se processou exatamente 100
            Assertions.assertEquals(totalMensagens, totalProcessadoSucesso.get(), "Erro: Número de processamentos divergente!");

            // Valida se o RabbitMQ foi chamado exatamente 100 vezes
            Mockito.verify(rabbitTemplate, Mockito.times(totalMensagens))
                    .convertAndSend(Mockito.anyString(), Mockito.anyString(), Mockito.any(Object.class));
        }
    }
}