package inv.it;

import inv.InvServiceApplication;
import inv.checkout.domain.model.StatusVenda;
import inv.checkout.infrastructure.messaging.VendaEventHandler;
import inv.checkout.infrastructure.persistence.PagamentoRepository;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.checkout.infrastructure.web.dto.ItemVendaRequest;
import inv.checkout.infrastructure.web.dto.VendaRequest;
import inv.checkout.usecase.PagamentoService;
import inv.checkout.usecase.VendaService;
import inv.inventory.api.ComandoEstoqueVenda;
import inv.inventory.api.EstoqueCommandService;
import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.UnidadeMedida;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import inv.shared.config.RabbitMQConfig;
import inv.shared.domain.MetodoPagamento;
import inv.shared.outbox.scheduler.OutboxScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Valida a política efetiva de retry e DLQ de application.properties com broker real. */
@Testcontainers
@SpringBootTest(classes = InvServiceApplication.class)
class PagamentoMensageriaIT {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");
    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3-management");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        registry.add("finance.projection.diaria.enabled", () -> "false");
    }

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean StringRedisTemplate redisTemplate;
    @MockitoBean OutboxScheduler outboxScheduler;
    @MockitoSpyBean VendaEventHandler handler;
    @MockitoSpyBean EstoqueCommandService estoque;
    @Autowired VendaService vendas;
    @Autowired PagamentoService pagamentos;
    @Autowired VendaRepository vendaRepository;
    @Autowired PagamentoRepository pagamentoRepository;
    @Autowired ProdutoRepository produtoRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired AmqpAdmin amqpAdmin;
    @Autowired RabbitListenerEndpointRegistry listeners;

    private Long vendaId;
    private Long pagamentoId;
    private Long produtoId;
    private String payload;

    @BeforeEach
    void preparar() {
        amqpAdmin.purgeQueue(RabbitMQConfig.QUEUE_PAGAMENTO_CONFIRMADO);
        amqpAdmin.purgeQueue(RabbitMQConfig.DLQ_QUEUE_PAGAMENTO_CONFIRMADO);
        Produto produto = new Produto();
        produto.setNome("Pagamento AMQP");
        produto.setCodigoBarras(UUID.randomUUID().toString().substring(0, 13));
        produto.setUnidadeMedida(UnidadeMedida.UN);
        produto.setPrecoVenda(new BigDecimal("100.00"));
        produto.setEstoqueDisponivel(new BigDecimal("10.000"));
        produto.setEstoqueReservado(BigDecimal.ZERO);
        produto.setEstoqueMinimo(BigDecimal.ONE);
        produtoId = produtoRepository.save(produto).getId();
        vendaId = vendas.iniciarVenda(new VendaRequest(List.of(new ItemVendaRequest(produtoId, BigDecimal.ONE)))).getId();
        pagamentoId = pagamentos.criarIntencaoPagamento(vendaId, MetodoPagamento.PIX, new BigDecimal("100.00"), UUID.randomUUID());
        pagamentos.confirmarPagamentoWebhook(pagamentoId, new BigDecimal("100.00"), "NSU-AMQP");
        payload = "{\"vendaId\":" + vendaId + ",\"pagamentoId\":" + pagamentoId + "}";
        listeners.start();
    }

    @Test
    void duplicatasDevemReceberAckSemRetryNemDlq() {
        enviar(payload);
        aguardarConclusao();
        enviar(payload);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> verify(handler, times(2)).handlePagamentoConfirmado(payload));
        pararEConferirFilasVazias();
        verificarContabilizacaoUnica();
    }

    @Test
    void falhaTransitoriaAposBaixaDeveReverterETentarNovamente() {
        AtomicInteger tentativas = new AtomicInteger();
        EstoqueCommandService alvo = AopTestUtils.getUltimateTargetObject(estoque);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (tentativas.incrementAndGet() == 1) {
                throw new PessimisticLockingFailureException("Falha transitória após baixa para provar rollback");
            }
            return null;
        }).when(alvo).confirmarBaixa(any(ComandoEstoqueVenda.class));

        enviar(payload);
        aguardarConclusao();
        pararEConferirFilasVazias();
        verify(handler, times(2)).handlePagamentoConfirmado(payload);
        assertThat(tentativas.get()).isEqualTo(2);
        verificarContabilizacaoUnica();
    }

    @ParameterizedTest
    @ValueSource(strings = {"json-invalido", "{\"vendaId\":-1,\"pagamentoId\":-1}"})
    void mensagemInvalidaDeveIrParaDlqAposPoliticaEfetivaDeRetry(String invalido) {
        enviar(invalido);
        verificarDlq(invalido);
        // O interceptor configurado tenta inclusive AmqpRejectAndDontRequeueException três vezes.
        verify(handler, times(3)).handlePagamentoConfirmado(invalido);
        verificarSemContabilizacao();
    }

    @Test
    void falhaTransitoriaPersistenteDeveEsgotarTresTentativasEReterRollbackNaDlq() {
        EstoqueCommandService alvo = AopTestUtils.getUltimateTargetObject(estoque);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new PessimisticLockingFailureException("Falha transitória persistente após baixa");
        }).when(alvo).confirmarBaixa(any(ComandoEstoqueVenda.class));

        enviar(payload);
        verificarDlq(payload);
        verify(handler, times(3)).handlePagamentoConfirmado(payload);
        verificarSemContabilizacao();
    }

    private void enviar(String mensagem) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.PDV_EXCHANGE, RabbitMQConfig.PDV_ROUTING_KEY, mensagem);
    }

    private void aguardarConclusao() {
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(vendaRepository.findById(vendaId).orElseThrow().getStatus()).isEqualTo(StatusVenda.CONCLUIDA));
    }

    private void verificarDlq(String original) {
        Message mensagem = rabbitTemplate.receive(RabbitMQConfig.DLQ_QUEUE_PAGAMENTO_CONFIRMADO, 15000);
        assertThat(mensagem).isNotNull();
        // O conversor JSON serializa a String; a mensagem morta conserva o corpo enviado.
        assertThat(new String(mensagem.getBody(), StandardCharsets.UTF_8)).contains(original.replace("\"", "\\\""));
        assertThat(mensagem.getMessageProperties().getHeaders()).containsKey("x-death");
        pararEConferirFilasVazias();
    }

    private void pararEConferirFilasVazias() {
        listeners.stop(); // Espera as entregas em andamento terminarem, incluindo ACK/reject.
        assertThat(amqpAdmin.getQueueInfo(RabbitMQConfig.QUEUE_PAGAMENTO_CONFIRMADO).getMessageCount()).isZero();
        assertThat(amqpAdmin.getQueueInfo(RabbitMQConfig.DLQ_QUEUE_PAGAMENTO_CONFIRMADO).getMessageCount()).isZero();
    }

    private void verificarContabilizacaoUnica() {
        assertThat(vendaRepository.findById(vendaId).orElseThrow().getValorPago()).isEqualByComparingTo("100.00");
        assertThat(pagamentoRepository.findById(pagamentoId).orElseThrow().isContabilizado()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lancamentos_financeiros WHERE venda_id = ?", Long.class, vendaId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM movimentacoes_estoque WHERE venda_id = ? AND tipo = 'SAIDA'", Long.class, vendaId)).isEqualTo(1L);
        assertThat(produtoRepository.findById(produtoId).orElseThrow().getEstoqueDisponivel()).isEqualByComparingTo("9.000");
        assertThat(produtoRepository.findById(produtoId).orElseThrow().getEstoqueReservado()).isEqualByComparingTo("0.000");
    }

    private void verificarSemContabilizacao() {
        assertThat(vendaRepository.findById(vendaId).orElseThrow().getStatus()).isEqualTo(StatusVenda.AGUARDANDO_PAGAMENTO);
        assertThat(vendaRepository.findById(vendaId).orElseThrow().getValorPago()).isEqualByComparingTo("0.00");
        assertThat(pagamentoRepository.findById(pagamentoId).orElseThrow().isContabilizado()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lancamentos_financeiros WHERE venda_id = ?", Long.class, vendaId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM movimentacoes_estoque WHERE venda_id = ? AND tipo = 'SAIDA'", Long.class, vendaId)).isZero();
        assertThat(produtoRepository.findById(produtoId).orElseThrow().getEstoqueDisponivel()).isEqualByComparingTo("9.000");
        assertThat(produtoRepository.findById(produtoId).orElseThrow().getEstoqueReservado()).isEqualByComparingTo("1.000");
    }
}
