/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.outbox.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import inv.shared.outbox.model.Outbox;
import inv.shared.outbox.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxProcessorTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private ObjectMapper objectMapper;
    private OutboxProcessor outboxProcessor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        outboxProcessor = new OutboxProcessor(outboxRepository, rabbitTemplate, objectMapper, 3000L);
    }

    @Test
    @DisplayName("DEVE retornar false sem interagir com broker quando outbox estiver vazia")
    void deveRetornarFalseQuandoOutboxVazia() {
        when(outboxRepository.findNextMessageToProcess()).thenReturn(Optional.empty());

        boolean processado = outboxProcessor.processNext();

        assertThat(processado).isFalse();
        verifyNoInteractions(rabbitTemplate);
        verify(outboxRepository, never()).delete(any());
    }

    @Test
    @DisplayName("DEVE validar que confirmTimeoutMs seja positivo")
    void deveRejeitarConfirmTimeoutNaoPositivo() {
        assertThatThrownBy(() -> new OutboxProcessor(outboxRepository, rabbitTemplate, objectMapper, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    @DisplayName("DEVE publicar JSON UTF-8 persistente sem __TypeId__ para version 0 e 1 e concluir após Confirm(ack=true)")
    void devePublicarJsonSemTypeIdEConcluirAposConfirmPositivo(int version) {
        Outbox outbox = criarOutbox(42L, "pdv.exchange", "venda.pagamento.confirmado",
                "java.util.HashMap", "{\"vendaId\":10,\"pagamentoId\":50}", version);
        when(outboxRepository.findNextMessageToProcess()).thenReturn(Optional.of(outbox));

        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        boolean processado = outboxProcessor.processNext();

        assertThat(processado).isTrue();
        verify(outboxRepository, times(1)).delete(outbox);

        ArgumentCaptor<Message> msgCaptor = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<CorrelationData> cdCaptor = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate).send(
                org.mockito.ArgumentMatchers.eq("pdv.exchange"),
                org.mockito.ArgumentMatchers.eq("venda.pagamento.confirmado"),
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
                "java.util.HashMap", "{\"vendaId\":10}", 1);
        when(outboxRepository.findNextMessageToProcess()).thenReturn(Optional.of(outbox));

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
    @DisplayName("NO_ROUTE: DEVE preservar linha na outbox quando broker retornar basic.return apesar de Confirm(ack=true)")
    void devePreservarLinhaEmNoRoute() {
        Outbox outbox = criarOutbox(10L, "pdv.exchange", "rota.sem.fila",
                "java.util.HashMap", "{\"ok\":true}", 1);
        when(outboxRepository.findNextMessageToProcess()).thenReturn(Optional.of(outbox));

        doAnswer(invocation -> {
            Message sent = invocation.getArgument(2);
            CorrelationData cd = invocation.getArgument(3);
            cd.setReturned(new ReturnedMessage(sent, 312, "NO_ROUTE", "pdv.exchange", "rota.sem.fila"));
            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        Boolean resultado = executarObservandoEstado();

        assertThat(resultado).isNotEqualTo(Boolean.TRUE);
        verify(outboxRepository, never()).delete(any());
    }

    @Test
    @DisplayName("NACK: DEVE preservar linha na outbox quando broker responder Confirm(ack=false)")
    void devePreservarLinhaEmNack() {
        Outbox outbox = criarOutbox(11L, "pdv.exchange", "venda.pagamento.confirmado",
                "java.util.HashMap", "{\"ok\":true}", 1);
        when(outboxRepository.findNextMessageToProcess()).thenReturn(Optional.of(outbox));

        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(false, "nack do broker"));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        Boolean resultado = executarObservandoEstado();

        assertThat(resultado).isNotEqualTo(Boolean.TRUE);
        verify(outboxRepository, never()).delete(any());
    }

    @Test
    @DisplayName("404 NOT_FOUND assíncrono: DEVE preservar linha quando fechamento de canal completar confirm com ack=false")
    void devePreservarLinhaEm404Assincrono() {
        Outbox outbox = criarOutbox(12L, "exchange.inexistente", "rk",
                "java.util.HashMap", "{\"ok\":true}", 1);
        when(outboxRepository.findNextMessageToProcess()).thenReturn(Optional.of(outbox));

        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(3);
            cd.getFuture().complete(new CorrelationData.Confirm(
                    false,
                    "channel error; protocol method: #method<channel.close>(reply-code=404, reply-text=NOT_FOUND - no exchange 'exchange.inexistente' in vhost '/')"));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        Boolean resultado = executarObservandoEstado();

        assertThat(resultado).isNotEqualTo(Boolean.TRUE);
        verify(outboxRepository, never()).delete(any());
    }

    @Test
    @DisplayName("Conexão: DEVE preservar linha quando send falhar com AmqpConnectException")
    void devePreservarLinhaEmFalhaDeConexao() {
        Outbox outbox = criarOutbox(13L, "pdv.exchange", "venda.pagamento.confirmado",
                "java.util.HashMap", "{\"ok\":true}", 1);
        when(outboxRepository.findNextMessageToProcess()).thenReturn(Optional.of(outbox));

        doThrow(new AmqpConnectException(new RuntimeException("Connection refused")))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        Boolean resultado = executarObservandoEstado();

        assertThat(resultado).isNotEqualTo(Boolean.TRUE);
        verify(outboxRepository, never()).delete(any());
    }

    @Test
    @DisplayName("Timeout: DEVE respeitar outbox.publisher.confirm-timeout-ms e preservar linha quando broker não confirmar a tempo")
    void deveRespeitarConfirmTimeoutEPreservarLinha() {
        OutboxProcessor processorCurto = new OutboxProcessor(outboxRepository, rabbitTemplate, objectMapper, 100L);
        Outbox outbox = criarOutbox(14L, "pdv.exchange", "venda.pagamento.confirmado",
                "java.util.HashMap", "{\"ok\":true}", 1);
        when(outboxRepository.findNextMessageToProcess()).thenReturn(Optional.of(outbox));

        long inicioMs = System.currentTimeMillis();
        Boolean resultado;
        try {
            resultado = processorCurto.processNext();
        } catch (RuntimeException ignored) {
            resultado = null;
        }
        long duracaoMs = System.currentTimeMillis() - inicioMs;

        assertThat(resultado).isNotEqualTo(Boolean.TRUE);
        assertThat(duracaoMs).isLessThan(2000L);
        verify(outboxRepository, never()).delete(any());
    }

    private Boolean executarObservandoEstado() {
        try {
            return outboxProcessor.processNext();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Outbox criarOutbox(
            Long id, String exchange, String routingKey, String eventType, String payload, int version) {
        return new Outbox(id, exchange, routingKey, payload, eventType, version, LocalDateTime.now());
    }
}
