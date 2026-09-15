package inv.inventory;

import common.exception.BusinessException;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.checkout.infrastructure.web.dto.ItemVendaRequest;
import inv.checkout.infrastructure.web.dto.VendaRequest;
import inv.checkout.usecase.VendaService;
import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.UnidadeMedida;
import inv.inventory.infrastructure.persistence.MovimentacaoRepository;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Teste de concorrência e integridade física conforme especificação do ADR-0002 (Seção 6).
 * Valida a prevenção de overselling via SQL atômico e ausência de deadlocks sob alta concorrência.
 */
@Testcontainers
@SpringBootTest
class EstoqueConcorrenciaIntegrationTest {

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

    @Autowired
    private VendaService vendaService;

    @Autowired
    private ProdutoRepository produtoRepository;

    @Autowired
    private VendaRepository vendaRepository;

    @Autowired
    private MovimentacaoRepository movimentacaoRepository;

    private Long produtoId;

    @BeforeEach
    void setUp() {
        movimentacaoRepository.deleteAll();
        vendaRepository.deleteAll();
        produtoRepository.deleteAll();

        Produto produto = new Produto();
        produto.setNome("Ração Concorrência ADR-0002");
        produto.setCodigoBarras("7891234567890");
        produto.setUnidadeMedida(UnidadeMedida.UN);
        produto.setPrecoVenda(new BigDecimal("50.00"));
        produto.setEstoqueDisponivel(new BigDecimal("5.000"));
        produto.setEstoqueReservado(new BigDecimal("0.000"));
        produto.setEstoqueMinimo(new BigDecimal("2.000"));

        Produto salvo = produtoRepository.saveAndFlush(produto);
        this.produtoId = salvo.getId();
    }

    @Test
    @DisplayName("ADR-0002 Seção 6: 50 threads disputando 5 unidades de estoque devem resultar em exatamente 5 sucessos e 45 BusinessException sem deadlock")
    void deveGarantirConsistenciaSobConcorrenciaDe50Threads() throws InterruptedException {
        int totalThreads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(totalThreads);

        AtomicInteger sucessos = new AtomicInteger(0);
        AtomicInteger businessExceptions = new AtomicInteger(0);
        List<Throwable> errosInesperados = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < totalThreads; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    VendaRequest request = new VendaRequest(List.of(
                            new ItemVendaRequest(produtoId, new BigDecimal("1.000"))
                    ));
                    vendaService.iniciarVenda(request);
                    sucessos.incrementAndGet();
                } catch (BusinessException be) {
                    businessExceptions.incrementAndGet();
                } catch (Throwable t) {
                    Throwable cause = t;
                    while (cause != null && !(cause instanceof BusinessException)) {
                        cause = cause.getCause();
                    }
                    if (cause instanceof BusinessException) {
                        businessExceptions.incrementAndGet();
                    } else {
                        System.err.println("ERRO NA THREAD: " + t.getClass() + " -> " + t.getMessage() + " | CAUSE: " + t.getCause());
                        if (t.getCause() != null) {
                            t.getCause().printStackTrace();
                        }
                        errosInesperados.add(t);
                    }
                } finally {
                    endGate.countDown();
                }
            });
        }

        // Disparo simultâneo (start gate)
        startGate.countDown();

        boolean finalizado = endGate.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // 1. Ausência de timeouts e erros de infraestrutura/deadlocks
        assertTrue(finalizado, "O teste demorou mais de 30 segundos para concluir.");
        assertTrue(errosInesperados.isEmpty(), "Ocorreram erros inesperados (deadlock ou infra): " + errosInesperados);

        // 2. Contagens de aceite do ADR-0002
        assertEquals(5, sucessos.get(), "Exatamente 5 threads devem completar a reserva com sucesso.");
        assertEquals(45, businessExceptions.get(), "Exatamente 45 threads devem receber BusinessException por esgotamento de estoque.");

        // 3. Consistência física do banco de dados
        Produto produtoFinal = produtoRepository.findById(produtoId).orElseThrow();
        assertEquals(0, new BigDecimal("0.000").compareTo(produtoFinal.getEstoqueDisponivel()),
                "Estoque disponível final deve ser exatamente 0.000, mas foi: " + produtoFinal.getEstoqueDisponivel());
        assertEquals(0, new BigDecimal("5.000").compareTo(produtoFinal.getEstoqueReservado()),
                "Estoque reservado final deve ser exatamente 5.000, mas foi: " + produtoFinal.getEstoqueReservado());
        assertEquals(0, new BigDecimal("5.000").compareTo(
                produtoFinal.getEstoqueDisponivel().add(produtoFinal.getEstoqueReservado())),
                "Soma de estoque_disponivel + estoque_reservado deve ser exatamente igual a 5.000");

        // 4. Integridade das vendas criadas
        assertEquals(5, vendaRepository.count(), "Exatamente 5 vendas devem estar persistidas no banco.");
    }
}
