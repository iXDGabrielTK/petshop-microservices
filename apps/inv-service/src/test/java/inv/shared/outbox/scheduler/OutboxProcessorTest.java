/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.outbox.scheduler;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import inv.shared.config.DateTimeConfig;
import inv.shared.outbox.metrics.OutboxMetrics;
import inv.shared.outbox.model.Outbox;
import inv.shared.outbox.repository.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxProcessorTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-11-24T15:00:00Z");

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private Clock clock;
    private ObjectMapper objectMapper;
    private SimpleMeterRegistry meterRegistry;
    private OutboxMetrics outboxMetrics;
    private OutboxDeliveryStep deliveryStep;
    private OutboxFailureRecorder failureRecorder;
    private OutboxProcessor outboxProcessor;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_INSTANT, DateTimeConfig.ZONE_SAO_PAULO);
        objectMapper = new ObjectMapper();
        meterRegistry = new SimpleMeterRegistry();
        outboxMetrics = new OutboxMetrics(outboxRepository, meterRegistry, clock);
        deliveryStep = new OutboxDeliveryStep(outboxRepository, rabbitTemplate, objectMapper, 3000L);
        failureRecorder = new OutboxFailureRecorder(outboxRepository, outboxMetrics, clock, 5, 2000L, 60000L);
        outboxProcessor = new OutboxProcessor(deliveryStep, failureRecorder, outboxMetrics, clock);
    }

    @Test
    @DisplayName("DEVE retornar EMPTY e false sem interagir com broker quando outbox estiver vazia")
    void deveRetornarEmptyQuandoOutboxVazia() {
        when(outboxRepository.findNextMessageToProcess(any(LocalDateTime.class))).thenReturn(Optional.empty());

        assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.EMPTY);
        assertThat(outboxProcessor.processNext()).isFalse();
        verifyNoInteractions(rabbitTemplate);
        verify(outboxRepository, never()).delete(any());
        verify(outboxRepository, never()).recordFailure(anyLong(), anyString(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("DEVE validar parâmetros positivos de timeout e retry")
    void deveRejeitarConfiguracoesInvalidas() {
        assertThatThrownBy(() -> new OutboxDeliveryStep(outboxRepository, rabbitTemplate, objectMapper, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxFailureRecorder(outboxRepository, outboxMetrics, clock, 0, 2000L, 60000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxFailureRecorder(outboxRepository, outboxMetrics, clock, 5, 0L, 60000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxFailureRecorder(outboxRepository, outboxMetrics, clock, 5, 5000L, 1000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    @DisplayName("DEVE publicar JSON UTF-8 persistente sem __TypeId__ para version 0 e 1 e concluir após Confirm(ack=true)")
    void devePublicarJsonSemTypeIdEConcluirAposConfirmPositivo(int version) {
        Outbox outbox = criarOutbox(42L, "pdv.exchange", "venda.pagamento.confirmado",
                "java.util.HashMap", "{\"vendaId\":10,\"pagamentoId\":50}", version, 0);
        when(outboxRepository.findNextMessageToProcess(any(LocalDateTime.class))).thenReturn(Optional.of(outbox));

        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        OutboxStepResult resultado = outboxProcessor.processNextStep();

        assertThat(resultado).isEqualTo(OutboxStepResult.DELIVERED);
        verify(outboxRepository, times(1)).delete(outbox);
        verify(outboxRepository, times(1)).flush();

        ArgumentCaptor<Message> msgCaptor = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<CorrelationData> cdCaptor = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate).send(
                eq("pdv.exchange"),
                eq("venda.pagamento.confirmado"),
                msgCaptor.capture(),
                cdCaptor.capture());

        Message enviada = msgCaptor.getValue();
        CorrelationData cd = cdCaptor.getValue();
        MessageProperties props = enviada.getMessageProperties();

        assertThat(new String(enviada.getBody(), StandardCharsets.UTF_8)).isEqualTo(outbox.getPayload());
        assertThat(props.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(props.getContentEncoding()).isEqualTo(StandardCharsets.UTF_8.name());
        assertThat(props.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(props.getMessageId()).isEqualTo("outbox-42");
        assertThat(props.getCorrelationId()).startsWith("outbox-42-").isEqualTo(cd.getId());
        assertThat(props.getHeaders())
                .containsEntry("x-outbox-id", 42L)
                .containsEntry("x-event-version", version)
                .doesNotContainKey("__TypeId__");
    }

    @Test
    @DisplayName("DEVE manter messageId estável e gerar correlationId distinto entre tentativas")
    void deveManterMessageIdEstavelECorrelationIdUnicoPorTentativa() {
        Outbox outbox = criarOutbox(99L, "pdv.exchange", "venda.estornada",
                "java.util.HashMap", "{\"vendaId\":10}", 1, 0);
        when(outboxRepository.findNextMessageToProcess(any(LocalDateTime.class))).thenReturn(Optional.of(outbox));

        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        assertThat(outboxProcessor.processNext()).isTrue();
        assertThat(outboxProcessor.processNext()).isTrue();

        ArgumentCaptor<Message> msgCaptor = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<CorrelationData> cdCaptor = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate, times(2)).send(anyString(), anyString(), msgCaptor.capture(), cdCaptor.capture());

        Message primeira = msgCaptor.getAllValues().get(0);
        Message segunda = msgCaptor.getAllValues().get(1);
        assertThat(primeira.getMessageProperties().getMessageId()).isEqualTo("outbox-99");
        assertThat(segunda.getMessageProperties().getMessageId()).isEqualTo("outbox-99");

        String corr1 = cdCaptor.getAllValues().get(0).getId();
        String corr2 = cdCaptor.getAllValues().get(1).getId();
        assertThat(corr1).startsWith("outbox-99-");
        assertThat(corr2).startsWith("outbox-99-").isNotEqualTo(corr1);
    }

    @Test
    @DisplayName("Poison pill: ClassNotFoundException DEVE mover imediatamente para QUARANTINED e retornar FAILED_CONTINUE")
    void deveQuarentenarImediatamenteQuandoClasseNaoExistir() {
        Outbox outbox = criarOutbox(1L, "pdv.exchange", "rk",
                "inv.inexistente.EventoFantasma", "{}", 1, 0);
        when(outboxRepository.findNextMessageToProcess(any(LocalDateTime.class))).thenReturn(Optional.of(outbox));
        when(outboxRepository.recordFailure(anyLong(), anyString(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        OutboxStepResult resultado = outboxProcessor.processNextStep();

        assertThat(resultado).isEqualTo(OutboxStepResult.FAILED_CONTINUE);
        verifyNoInteractions(rabbitTemplate);
        LocalDateTime agora = LocalDateTime.now(clock);
        verify(outboxRepository).recordFailure(
                eq(1L), eq(Outbox.STATUS_QUARANTINED), eq(1), isNull(), eq(agora),
                eq("CLASS_NOT_FOUND"), anyString(), eq(agora));
        Counter quarantinedCounter = meterRegistry.find(OutboxMetrics.METRIC_QUARANTINED_TOTAL)
                .tag("error_type", "CLASS_NOT_FOUND")
                .counter();
        assertThat(quarantinedCounter).isNotNull();
        assertThat(quarantinedCounter.count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("Poison pill: JSON inválido e version negativa DEVEM mover imediatamente para QUARANTINED e continuar ciclo")
    void deveQuarentenarImediatamenteQuandoJsonInvalidoOuVersaoNegativa() {
        Outbox jsonInvalido = criarOutbox(2L, "pdv.exchange", "rk",
                "java.util.HashMap", "{json-quebrado", 1, 0);
        Outbox versaoNegativa = criarOutbox(3L, "pdv.exchange", "rk",
                "java.util.HashMap", "{}", -1, 0);

        when(outboxRepository.findNextMessageToProcess(any(LocalDateTime.class)))
                .thenReturn(Optional.of(jsonInvalido), Optional.of(versaoNegativa));
        when(outboxRepository.recordFailure(anyLong(), anyString(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.FAILED_CONTINUE);
        assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.FAILED_CONTINUE);

        LocalDateTime agora = LocalDateTime.now(clock);
        verify(outboxRepository).recordFailure(
                eq(2L), eq(Outbox.STATUS_QUARANTINED), eq(1), isNull(), eq(agora),
                eq("INVALID_PAYLOAD"), anyString(), eq(agora));
        verify(outboxRepository).recordFailure(
                eq(3L), eq(Outbox.STATUS_QUARANTINED), eq(1), isNull(), eq(agora),
                eq("INVALID_VERSION"), anyString(), eq(agora));
    }

    @Test
    @DisplayName("NO_ROUTE: DEVE agendar backoff via Clock e retornar FAILED_CONTINUE sem deletar")
    void deveRegistrarBackoffEContinuarEmNoRoute() {
        Outbox outbox = criarOutbox(10L, "pdv.exchange", "rota.sem.fila",
                "java.util.HashMap", "{\"ok\":true}", 1, 0);
        when(outboxRepository.findNextMessageToProcess(any(LocalDateTime.class))).thenReturn(Optional.of(outbox));
        when(outboxRepository.recordFailure(anyLong(), anyString(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        doAnswer(invocation -> {
            Message sent = invocation.getArgument(2);
            CorrelationData cd = invocation.getArgument(3);
            cd.setReturned(new ReturnedMessage(sent, 312, "NO_ROUTE", "pdv.exchange", "rota.sem.fila"));
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        OutboxStepResult resultado = outboxProcessor.processNextStep();

        assertThat(resultado).isEqualTo(OutboxStepResult.FAILED_CONTINUE);
        verify(outboxRepository, never()).delete(any());
        LocalDateTime agora = LocalDateTime.now(clock);
        verify(outboxRepository).recordFailure(
                eq(10L), eq(Outbox.STATUS_PENDING), eq(1), eq(agora.plusSeconds(2)), eq(agora),
                eq("NO_ROUTE"), anyString(), isNull());
    }

    @Test
    @DisplayName("404 NOT_FOUND assíncrono: DEVE classificar como roteamento (FAILED_CONTINUE) com backoff")
    void deveClassificar404ComoRoteamentoEContinuar() {
        Outbox outbox = criarOutbox(12L, "exchange.inexistente", "rk",
                "java.util.HashMap", "{\"ok\":true}", 1, 1);
        when(outboxRepository.findNextMessageToProcess(any(LocalDateTime.class))).thenReturn(Optional.of(outbox));
        when(outboxRepository.recordFailure(anyLong(), anyString(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(
                    false,
                    "channel error; protocol method: #method<channel.close>(reply-code=404, reply-text=NOT_FOUND - no exchange 'exchange.inexistente' in vhost '/')"));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        OutboxStepResult resultado = outboxProcessor.processNextStep();

        assertThat(resultado).isEqualTo(OutboxStepResult.FAILED_CONTINUE);
        LocalDateTime agora = LocalDateTime.now(clock);
        verify(outboxRepository).recordFailure(
                eq(12L), eq(Outbox.STATUS_PENDING), eq(2), eq(agora.plusSeconds(4)), eq(agora),
                eq("AMQP_404_NOT_FOUND"), anyString(), isNull());
    }

    @Test
    @DisplayName("basic.nack puro (reason nulo) e shutdown desconhecido DEVEM pausar ciclo (FAILED_PAUSE)")
    void devePausarCicloEmNackPuroOuShutdown() {
        Outbox nackPuro = criarOutbox(11L, "pdv.exchange", "venda.pagamento.confirmado",
                "java.util.HashMap", "{\"ok\":true}", 1, 0);
        Outbox shutdown = criarOutbox(15L, "pdv.exchange", "venda.pagamento.confirmado",
                "java.util.HashMap", "{\"ok\":true}", 1, 0);
        when(outboxRepository.findNextMessageToProcess(any(LocalDateTime.class)))
                .thenReturn(Optional.of(nackPuro), Optional.of(shutdown));
        when(outboxRepository.recordFailure(anyLong(), anyString(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(false, null));
            return null;
        }).doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(false, "connection forced close"));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.FAILED_PAUSE);
        assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.FAILED_PAUSE);
        assertThat(outboxProcessor.processNext()).isFalse();

        verify(outboxRepository, never()).delete(any());
        verify(outboxRepository, times(3))
                .recordFailure(anyLong(), eq(Outbox.STATUS_PENDING), eq(1), any(), any(), eq("AMQP_NACK"), anyString(), isNull());
    }

    @Test
    @DisplayName("Conexão: DEVE registrar backoff, preservar linha e retornar FAILED_PAUSE em AmqpConnectException")
    void devePausarERegistrarBackoffEmFalhaDeConexao() {
        Outbox outbox = criarOutbox(13L, "pdv.exchange", "venda.pagamento.confirmado",
                "java.util.HashMap", "{\"ok\":true}", 1, 4);
        when(outboxRepository.findNextMessageToProcess(any(LocalDateTime.class))).thenReturn(Optional.of(outbox));
        when(outboxRepository.recordFailure(anyLong(), anyString(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        doThrow(new AmqpConnectException(new RuntimeException("Connection refused")))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        OutboxStepResult resultado = outboxProcessor.processNextStep();

        assertThat(resultado).isEqualTo(OutboxStepResult.FAILED_PAUSE);
        verify(outboxRepository, never()).delete(any());
        LocalDateTime agora = LocalDateTime.now(clock);
        verify(outboxRepository).recordFailure(
                eq(13L), eq(Outbox.STATUS_QUARANTINED), eq(5), isNull(), eq(agora),
                eq("AMQP_CONNECT_ERROR"), anyString(), eq(agora));
    }

    @Test
    @DisplayName("Timeout: DEVE respeitar outbox.publisher.confirm-timeout-ms, registrar backoff e pausar")
    void deveRespeitarConfirmTimeoutEPausar() {
        OutboxDeliveryStep stepCurto = new OutboxDeliveryStep(outboxRepository, rabbitTemplate, objectMapper, 100L);
        OutboxProcessor processorCurto = new OutboxProcessor(stepCurto, failureRecorder, outboxMetrics, clock);
        Outbox outbox = criarOutbox(14L, "pdv.exchange", "venda.pagamento.confirmado",
                "java.util.HashMap", "{\"ok\":true}", 1, 0);
        when(outboxRepository.findNextMessageToProcess(any(LocalDateTime.class))).thenReturn(Optional.of(outbox));
        when(outboxRepository.recordFailure(anyLong(), anyString(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        long inicioMs = System.currentTimeMillis();
        OutboxStepResult resultado = processorCurto.processNextStep();
        long duracaoMs = System.currentTimeMillis() - inicioMs;

        assertThat(resultado).isEqualTo(OutboxStepResult.FAILED_PAUSE);
        assertThat(duracaoMs).isLessThan(2000L);
        verify(outboxRepository, never()).delete(any());
        verify(outboxRepository).recordFailure(
                eq(14L), eq(Outbox.STATUS_PENDING), eq(1), any(), any(),
                eq("AMQP_CONFIRM_TIMEOUT"), anyString(), isNull());
    }

    @Test
    @DisplayName("Persistência pós-confirm em delete/flush DEVE ir para backoff PENDING (nunca quarentena imediata) e pausar")
    void deveTratarFalhaDePersistenciaPosConfirmEmDeleteComoTransitoria() {
        Outbox outbox = criarOutbox(20L, "pdv.exchange", "venda.estornada",
                "java.util.HashMap", "{\"vendaId\":7}", 1, 0);
        when(outboxRepository.findNextMessageToProcess(any(LocalDateTime.class))).thenReturn(Optional.of(outbox));
        when(outboxRepository.recordFailure(anyLong(), anyString(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        doThrow(new DataAccessResourceFailureException("connection reset during delete"))
                .when(outboxRepository).flush();

        OutboxStepResult resultado = outboxProcessor.processNextStep();

        assertThat(resultado).isEqualTo(OutboxStepResult.FAILED_PAUSE);
        LocalDateTime agora = LocalDateTime.now(clock);
        verify(outboxRepository).recordFailure(
                eq(20L), eq(Outbox.STATUS_PENDING), eq(1), eq(agora.plusSeconds(2)), eq(agora),
                eq("POST_CONFIRM_PERSISTENCE_ERROR"), anyString(), isNull());
    }

    @Test
    @DisplayName("Persistência pós-confirm no commit do REQUIRES_NEW DEVE gravar fallback via snapshot e pausar")
    void deveTratarFalhaNoCommitPosConfirmViaSnapshotEPausar() {
        OutboxDeliveryStep stepMock = mock(OutboxDeliveryStep.class);
        OutboxProcessor processor = new OutboxProcessor(stepMock, failureRecorder, outboxMetrics, clock);

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            AtomicReference<OutboxAttemptSnapshot> ref = invocation.getArgument(1);
            ref.set(new OutboxAttemptSnapshot(21L, 0, "pdv.exchange", "venda.estornada"));
            throw new TransactionSystemException("commit failure");
        }).when(stepMock).deliverNext(any(LocalDateTime.class), any());
        when(outboxRepository.recordFailure(anyLong(), anyString(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(1);

        OutboxStepResult resultado = processor.processNextStep();

        assertThat(resultado).isEqualTo(OutboxStepResult.FAILED_PAUSE);
        LocalDateTime agora = LocalDateTime.now(clock);
        verify(outboxRepository).recordFailure(
                eq(21L), eq(Outbox.STATUS_PENDING), eq(1), eq(agora.plusSeconds(2)), eq(agora),
                eq("POST_CONFIRM_PERSISTENCE_ERROR"), anyString(), isNull());
    }

    @Test
    @DisplayName("Backoff exponencial DEVE respeitar teto maxDelayMs, truncar errorType em 64 chars e tolerar 0 linhas (no-op)")
    void deveCalcularBackoffSemOverflowTruncarErroETolerarNoOp() {
        assertThat(failureRecorder.calculateBackoffMs(1)).isEqualTo(2000L);
        assertThat(failureRecorder.calculateBackoffMs(2)).isEqualTo(4000L);
        assertThat(failureRecorder.calculateBackoffMs(3)).isEqualTo(8000L);
        assertThat(failureRecorder.calculateBackoffMs(50)).isEqualTo(60000L);

        when(outboxRepository.recordFailure(anyLong(), anyString(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(0);
        String erroLongo = "E".repeat(100);
        OutboxAttemptSnapshot snapshot = new OutboxAttemptSnapshot(30L, 0, "ex", "rk");

        failureRecorder.recordFailure(snapshot, OutboxFailureCategory.BROKER_UNAVAILABLE, erroLongo, null);

        verify(outboxRepository).recordFailure(
                eq(30L), eq(Outbox.STATUS_PENDING), eq(1), any(), any(),
                eq("E".repeat(64)), eq("E".repeat(64)), isNull());
        assertThat(meterRegistry.find(OutboxMetrics.METRIC_QUARANTINED_TOTAL).counter()).isNull();
    }

    @Test
    @DisplayName("Replay concluído (replayCount > 0) DEVE incrementar outbox.replay.completed.total e emitir log estruturado")
    void deveIncrementarReplayCompletedEEmitirLogEstruturadoQuandoReplayCountPositivo() {
        Outbox replayed = criarOutbox(77L, "pdv.exchange", "venda.estornada",
                "java.util.HashMap", "{\"vendaId\":10}", 0, 0);
        replayed.setReplayCount(2);
        when(outboxRepository.findNextMessageToProcess(any(LocalDateTime.class))).thenReturn(Optional.of(replayed));
        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        Logger logger = (Logger) LoggerFactory.getLogger(OutboxProcessor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.DELIVERED);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(meterRegistry.find(OutboxMetrics.METRIC_REPLAY_COMPLETED_TOTAL).counter().count()).isEqualTo(1.0d);
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(msg -> assertThat(msg)
                        .contains("event=outbox_replay_completed")
                        .contains("outboxId=77")
                        .contains("eventType=java.util.HashMap")
                        .contains("version=0")
                        .contains("replayCount=2")
                        .contains("messageId=outbox-77")
                        .contains("correlationId=outbox-77-"));
    }

    @Test
    @DisplayName("Entrega normal (replayCount = 0) ou rollback pós-confirm NÃO deve registrar outbox_replay_completed")
    void naoDeveRegistrarReplayCompletedEmEntregaNormalOuRollbackPosConfirm() {
        Outbox normal = criarOutbox(78L, "pdv.exchange", "venda.estornada",
                "java.util.HashMap", "{\"vendaId\":10}", 1, 0);
        Outbox replayFalho = criarOutbox(79L, "pdv.exchange", "venda.estornada",
                "java.util.HashMap", "{\"vendaId\":11}", 1, 0);
        replayFalho.setReplayCount(1);

        when(outboxRepository.findNextMessageToProcess(any(LocalDateTime.class)))
                .thenReturn(Optional.of(normal), Optional.of(replayFalho));
        when(outboxRepository.recordFailure(anyLong(), anyString(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        doNothing()
                .doThrow(new DataAccessResourceFailureException("Erro no flush"))
                .when(outboxRepository).flush();

        Logger logger = (Logger) LoggerFactory.getLogger(OutboxProcessor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.DELIVERED);
            assertThat(outboxProcessor.processNextStep()).isEqualTo(OutboxStepResult.FAILED_PAUSE);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(meterRegistry.find(OutboxMetrics.METRIC_REPLAY_COMPLETED_TOTAL).counter().count()).isZero();
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(msg -> msg.contains("outbox_replay_completed"));
    }

    @Test
    @DisplayName("OutboxScheduler DEVE continuar ciclo em DELIVERED e FAILED_CONTINUE e interromper em EMPTY ou FAILED_PAUSE")
    void deveControlarLoopDoOutboxSchedulerConformeResultadoDoPasso() {
        OutboxProcessor processorMock = mock(OutboxProcessor.class);
        OutboxScheduler scheduler = new OutboxScheduler(processorMock);

        when(processorMock.processNextStep())
                .thenReturn(OutboxStepResult.DELIVERED, OutboxStepResult.FAILED_CONTINUE, OutboxStepResult.EMPTY);
        scheduler.processOutbox();
        verify(processorMock, times(3)).processNextStep();

        when(processorMock.processNextStep())
                .thenReturn(OutboxStepResult.DELIVERED, OutboxStepResult.FAILED_PAUSE);
        scheduler.processOutbox();
        verify(processorMock, times(5)).processNextStep();

        when(processorMock.processNextStep()).thenThrow(new RuntimeException("erro inesperado"));
        scheduler.processOutbox();
        verify(processorMock, times(6)).processNextStep();
    }

    private static Outbox criarOutbox(
            Long id,
            String exchange,
            String routingKey,
            String eventType,
            String payload,
            int version,
            int attempts) {
        Outbox outbox = new Outbox();
        outbox.setId(id);
        outbox.setExchange(exchange);
        outbox.setRoutingKey(routingKey);
        outbox.setEventType(eventType);
        outbox.setPayload(payload);
        outbox.setVersion(version);
        outbox.setAttempts(attempts);
        outbox.setCreatedAt(LocalDateTime.now());
        return outbox;
    }
}
