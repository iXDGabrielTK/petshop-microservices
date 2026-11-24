/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.it;

import inv.InvServiceApplication;
import inv.checkout.domain.model.StatusVenda;
import inv.checkout.infrastructure.persistence.PagamentoRepository;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.checkout.infrastructure.web.dto.ItemVendaRequest;
import inv.checkout.infrastructure.web.dto.VendaRequest;
import inv.checkout.usecase.PagamentoService;
import inv.checkout.usecase.VendaService;
import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.UnidadeMedida;
import inv.inventory.infrastructure.messaging.EstoqueAtingiuMinimoEvent;
import inv.inventory.infrastructure.messaging.EstoqueBaixoMessage;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import inv.shared.config.RabbitMQConfig;
import inv.shared.domain.MetodoPagamento;
import inv.shared.outbox.model.Outbox;
import inv.shared.outbox.repository.OutboxRepository;
import inv.shared.outbox.scheduler.OutboxProcessor;
import inv.shared.outbox.scheduler.OutboxScheduler;
import inv.shared.outbox.scheduler.OutboxStepResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessagePropertiesBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(classes = InvServiceApplication.class, properties = "inv.scheduling.enabled=false")
class OutboxConfirmacaoERotasIT {

    public record EstoqueBaixoEspelho(
            int version,
            String eventId,
            String nomeProduto,
            BigDecimal estoqueAtual,
            BigDecimal estoqueMinimo) {}

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

        registry.add("finance.projection.diaria.enabled", () -> "false");
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private OutboxScheduler outboxScheduler;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxProcessor outboxProcessor;

    @Autowired
    private Clock clock;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private MessageConverter messageConverter;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private VendaService vendaService;

    @Autowired
    private PagamentoService pagamentoService;

    @Autowired
    private VendaRepository vendaRepository;

    @Autowired
    private PagamentoRepository pagamentoRepository;

    @Autowired
    private ProdutoRepository produtoRepository;

    @Autowired
    private RabbitListenerEndpointRegistry listeners;

    @BeforeEach
    void preparar() {
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
        listeners.start();
    }

    @AfterAll
    static void tearDownAll(@Autowired RabbitListenerEndpointRegistry registry) {
        if (registry != null) {
            registry.stop();
        }
    }

    @Test
    @DisplayName("Cenário 1 — NO_ROUTE deve preservar linha e liberar lock, e entrega deve ocorrer após restaurar binding")
    void cenario1_noRoutePreservaOutboxELiberaLockEEntregaAposRestaurarBinding() {
        String routingKeySemFila = "e1.no.route." + UUID.randomUUID();
        String jsonPayload = "{\"origem\":\"cenario-1\"}";

        Outbox outbox = new Outbox();
        outbox.setExchange(RabbitMQConfig.PDV_EXCHANGE);
        outbox.setRoutingKey(routingKeySemFila);
        outbox.setEventType("java.util.HashMap");
        outbox.setPayload(jsonPayload);
        outbox.setVersion(1);
        outbox.setCreatedAt(LocalDateTime.now());
        Outbox salvo = outboxRepository.saveAndFlush(outbox);
        Long outboxId = salvo.getId();

        OutboxStepResult primeiraTentativa = outboxProcessor.processNextStep();
        assertThat(primeiraTentativa).isEqualTo(OutboxStepResult.FAILED_CONTINUE);
        Outbox aposFalha = outboxRepository.findById(outboxId).orElseThrow();
        assertThat(aposFalha.getStatus()).isEqualTo(Outbox.STATUS_PENDING);
        assertThat(aposFalha.getAttempts()).isEqualTo(1);
        assertThat(aposFalha.getNextAttemptAt()).isNotNull();
        assertThat(lockLiberadoParaProximaTransacao(outboxId)).isTrue();

        String filaTemporaria = "e1.temp.queue." + UUID.randomUUID();
        try {
            amqpAdmin.declareQueue(new Queue(filaTemporaria, true, false, false));
            amqpAdmin.declareBinding(BindingBuilder
                    .bind(new Queue(filaTemporaria, true, false, false))
                    .to(new TopicExchange(RabbitMQConfig.PDV_EXCHANGE))
                    .with(routingKeySemFila));

            jdbcTemplate.update("UPDATE outbox SET next_attempt_at = NULL WHERE id = ?", outboxId);
            boolean segundaTentativa = outboxProcessor.processNext();
            assertThat(segundaTentativa).isTrue();
            assertThat(outboxRepository.findById(outboxId)).isEmpty();

            Message recebida = rabbitTemplate.receive(filaTemporaria, 5000);
            assertThat(recebida).isNotNull();
            MessageProperties props = recebida.getMessageProperties();
            assertThat(new String(recebida.getBody(), StandardCharsets.UTF_8)).isEqualTo(jsonPayload);
            assertThat(props.getMessageId()).isEqualTo("outbox-" + outboxId);
            assertThat(props.getCorrelationId()).startsWith("outbox-" + outboxId + "-");
            assertThat(props.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
            assertThat(props.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(((Number) props.getHeader("x-outbox-id")).longValue()).isEqualTo(outboxId);
            assertThat(((Number) props.getHeader("x-event-version")).intValue()).isEqualTo(1);
            assertThat(props.getHeaders()).doesNotContainKey("__TypeId__");
        } finally {
            amqpAdmin.deleteQueue(filaTemporaria);
        }
    }

    @Test
    @DisplayName("Cenário 2 — Exchange inexistente (404 NOT_FOUND) deve preservar linha na outbox e liberar lock")
    void cenario2_exchangeInexistente404PreservaOutboxELiberaLock() {
        Outbox outbox = new Outbox();
        outbox.setExchange("e1.exchange.inexistente." + UUID.randomUUID());
        outbox.setRoutingKey("e1.rk.404");
        outbox.setEventType("java.util.HashMap");
        outbox.setPayload("{\"origem\":\"cenario-2\"}");
        outbox.setVersion(1);
        outbox.setCreatedAt(LocalDateTime.now());
        Long outboxId = outboxRepository.saveAndFlush(outbox).getId();

        OutboxStepResult resultado = outboxProcessor.processNextStep();

        assertThat(resultado).isEqualTo(OutboxStepResult.FAILED_CONTINUE);
        Outbox aposFalha = outboxRepository.findById(outboxId).orElseThrow();
        assertThat(aposFalha.getStatus()).isEqualTo(Outbox.STATUS_PENDING);
        assertThat(aposFalha.getAttempts()).isEqualTo(1);
        assertThat(aposFalha.getNextAttemptAt()).isNotNull();
        assertThat(lockLiberadoParaProximaTransacao(outboxId)).isTrue();
    }

    @Test
    @DisplayName("Cenário 3 — Rota de pagamento E2E deve aceitar version 1, legado version 0 e enviar payload incompatível para DLQ")
    void cenario3_fluxoPagamentoPontaAPontaAceitaVersion1E0EEnviaIncompativelParaDlq() {
        Produto produto = new Produto();
        produto.setNome("Produto E1 Pagamento");
        produto.setCodigoBarras("789" + UUID.randomUUID().toString().substring(0, 10));
        produto.setUnidadeMedida(UnidadeMedida.UN);
        produto.setPrecoVenda(new BigDecimal("100.00"));
        produto.setEstoqueDisponivel(new BigDecimal("10.000"));
        produto.setEstoqueReservado(BigDecimal.ZERO);
        produto.setEstoqueMinimo(BigDecimal.ONE);
        Long produtoId = produtoRepository.saveAndFlush(produto).getId();

        // Fluxo 1: PagamentoListener grava version = 1
        Long venda1Id = vendaService.iniciarVenda(
                new VendaRequest(List.of(new ItemVendaRequest(produtoId, BigDecimal.ONE)))).getId();
        Long pag1Id = pagamentoService.criarIntencaoPagamento(
                venda1Id, MetodoPagamento.PIX, new BigDecimal("100.00"), UUID.randomUUID());
        pagamentoService.confirmarPagamentoWebhook(pag1Id, new BigDecimal("100.00"), "NSU-E1-V1");

        List<Outbox> pendentesV1 = outboxRepository.findAll();
        assertThat(pendentesV1).hasSize(1);
        assertThat(pendentesV1.getFirst().getVersion()).isEqualTo(1);

        assertThat(outboxProcessor.processNext()).isTrue();
        assertThat(outboxRepository.count()).isZero();

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(vendaRepository.findById(venda1Id).orElseThrow().getStatus())
                    .isEqualTo(StatusVenda.CONCLUIDA);
            assertThat(pagamentoRepository.findById(pag1Id).orElseThrow().isContabilizado()).isTrue();
        });

        // Fluxo 2: Registro legado gravado com version = 0
        Long venda2Id = vendaService.iniciarVenda(
                new VendaRequest(List.of(new ItemVendaRequest(produtoId, BigDecimal.ONE)))).getId();
        Long pag2Id = pagamentoService.criarIntencaoPagamento(
                venda2Id, MetodoPagamento.PIX, new BigDecimal("100.00"), UUID.randomUUID());
        pagamentoService.confirmarPagamentoWebhook(pag2Id, new BigDecimal("100.00"), "NSU-E1-V0");

        Long outboxV0Id = outboxRepository.findAll().getFirst().getId();
        jdbcTemplate.update("UPDATE outbox SET version = 0 WHERE id = ?", outboxV0Id);
        assertThat(outboxRepository.findById(outboxV0Id).orElseThrow().getVersion()).isZero();

        assertThat(outboxProcessor.processNext()).isTrue();
        assertThat(outboxRepository.count()).isZero();

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(vendaRepository.findById(venda2Id).orElseThrow().getStatus())
                    .isEqualTo(StatusVenda.CONCLUIDA);
            assertThat(pagamentoRepository.findById(pag2Id).orElseThrow().isContabilizado()).isTrue();
        });

        // Fluxo 3: Payload JSON incompatível sem __TypeId__ deve ir para a DLQ sem requeue
        MessageProperties props = MessagePropertiesBuilder.newInstance()
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .build();
        Message mensagemIncompativel = new Message(
                "{\"vendaId\":\"nao-numerico\",\"pagamentoId\":1}".getBytes(StandardCharsets.UTF_8), props);
        rabbitTemplate.send(RabbitMQConfig.PDV_EXCHANGE, RabbitMQConfig.PDV_ROUTING_KEY, mensagemIncompativel);

        Message dlqMsg = rabbitTemplate.receive(RabbitMQConfig.DLQ_QUEUE_PAGAMENTO_CONFIRMADO, 15000);
        assertThat(dlqMsg).isNotNull();
        assertThat(dlqMsg.getMessageProperties().getHeaders()).containsKey("x-death");
    }

    @Test
    @DisplayName("Cenário 4 — Rota de estoque baixo sem fila órfã devolve NO_ROUTE sem mail-service e entrega sem __TypeId__ após binding")
    void cenario4_rotaEstoqueBaixoSemFilaOrfaEDesserializacaoPorInferenciaSemTypeId() throws Exception {
        assertThat(amqpAdmin.getQueueInfo(RabbitMQConfig.QUEUE_LOW_STOCK)).isNull();

        String filaMailService = "inventory.v1.low-stock.send-email";
        amqpAdmin.deleteQueue(filaMailService);

        String eventId = "evt-e1-low-" + UUID.randomUUID();
        EstoqueBaixoMessage payload = new EstoqueBaixoMessage(
                1,
                eventId,
                "Ração Estoque Baixo E1",
                new BigDecimal("2.000"),
                new BigDecimal("5.000"));

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                eventPublisher.publishEvent(new EstoqueAtingiuMinimoEvent(payload)));

        List<Outbox> registrados = outboxRepository.findAll();
        assertThat(registrados).hasSize(1);
        Outbox outbox = registrados.getFirst();
        Long outboxId = outbox.getId();
        assertThat(outbox.getExchange()).isEqualTo(RabbitMQConfig.EXCHANGE_NAME);
        assertThat(outbox.getRoutingKey()).isEqualTo(RabbitMQConfig.ROUTING_KEY_LOW_STOCK);
        assertThat(outbox.getVersion()).isEqualTo(1);

        // Sem a fila do mail-service e com fila órfã desabilitada, broker deve sinalizar NO_ROUTE e preservar outbox
        OutboxStepResult tentativaSemConsumidor = outboxProcessor.processNextStep();
        assertThat(tentativaSemConsumidor).isEqualTo(OutboxStepResult.FAILED_CONTINUE);
        Outbox aposFalha = outboxRepository.findById(outboxId).orElseThrow();
        assertThat(aposFalha.getStatus()).isEqualTo(Outbox.STATUS_PENDING);
        assertThat(aposFalha.getAttempts()).isEqualTo(1);

        try {
            Queue mailQueue = new Queue(filaMailService, true, false, false);
            amqpAdmin.declareQueue(mailQueue);
            amqpAdmin.declareBinding(BindingBuilder
                    .bind(mailQueue)
                    .to(new TopicExchange(RabbitMQConfig.EXCHANGE_NAME))
                    .with(RabbitMQConfig.ROUTING_KEY_LOW_STOCK));

            jdbcTemplate.update("UPDATE outbox SET next_attempt_at = NULL WHERE id = ?", outboxId);
            boolean processado = outboxProcessor.processNext();
            assertThat(processado).isTrue();
            assertThat(outboxRepository.findById(outboxId)).isEmpty();

            Message rawMessage = rabbitTemplate.receive(filaMailService, 5000);
            assertThat(rawMessage).isNotNull();
            MessageProperties props = rawMessage.getMessageProperties();
            assertThat(props.getHeaders()).doesNotContainKey("__TypeId__");
            assertThat(props.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
            assertThat(props.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(props.getMessageId()).isEqualTo("outbox-" + outboxId);
            assertThat(((Number) props.getHeader("x-outbox-id")).longValue()).isEqualTo(outboxId);
            assertThat(((Number) props.getHeader("x-event-version")).intValue()).isEqualTo(1);

            rawMessage.getMessageProperties().setInferredArgumentType(EstoqueBaixoEspelho.class);
            Object convertido = messageConverter.fromMessage(rawMessage);
            assertThat(convertido).isInstanceOf(EstoqueBaixoEspelho.class);

            EstoqueBaixoEspelho espelho = (EstoqueBaixoEspelho) convertido;
            assertThat(espelho.version()).isEqualTo(1);
            assertThat(espelho.eventId()).isEqualTo(eventId);
            assertThat(espelho.nomeProduto()).isEqualTo("Ração Estoque Baixo E1");
            assertThat(espelho.estoqueAtual()).isEqualByComparingTo("2.000");
            assertThat(espelho.estoqueMinimo()).isEqualByComparingTo("5.000");
        } finally {
            amqpAdmin.deleteQueue(filaMailService);
        }
    }

    private boolean lockLiberadoParaProximaTransacao(Long esperadoId) {
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        LocalDateTime janelaFutura = LocalDateTime.now(clock).plusMinutes(5);
        Optional<Outbox> encontrado = requiresNew.execute(status -> outboxRepository.findNextMessageToProcess(janelaFutura));
        return encontrado != null && encontrado.isPresent() && esperadoId.equals(encontrado.get().getId());
    }
}
