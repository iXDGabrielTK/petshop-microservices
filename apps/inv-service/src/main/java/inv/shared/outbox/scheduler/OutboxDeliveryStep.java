/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.outbox.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import inv.shared.outbox.model.Outbox;
import inv.shared.outbox.repository.OutboxRepository;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessagePropertiesBuilder;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

@Component
class OutboxDeliveryStep {

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final long confirmTimeoutMs;

    OutboxDeliveryStep(
            OutboxRepository outboxRepository,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            @Value("${outbox.publisher.confirm-timeout-ms:3000}") long confirmTimeoutMs) {
        if (confirmTimeoutMs <= 0) {
            throw new IllegalArgumentException("outbox.publisher.confirm-timeout-ms deve ser maior que zero.");
        }
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean deliverNext(LocalDateTime agora, AtomicReference<OutboxAttemptSnapshot> attemptRef) {
        var messageOpt = outboxRepository.findNextMessageToProcess(agora);
        if (messageOpt.isEmpty()) {
            return false;
        }

        Outbox message = messageOpt.get();
        OutboxAttemptSnapshot snapshot = new OutboxAttemptSnapshot(
                message.getId(),
                message.getAttempts(),
                message.getExchange(),
                message.getRoutingKey(),
                message.getEventType(),
                message.getVersion(),
                message.getReplayCount(),
                null,
                null);
        attemptRef.set(snapshot);

        validarPayload(message, snapshot);
        snapshot = enviarEAguardarConfirmacao(message, snapshot, attemptRef);

        try {
            outboxRepository.delete(message);
            outboxRepository.flush();
        } catch (RuntimeException e) {
            throw new OutboxPublishException(
                    snapshot,
                    OutboxFailureCategory.POST_CONFIRM_PERSISTENCE,
                    "POST_CONFIRM_PERSISTENCE_ERROR",
                    "Falha de persistência pós-confirm AMQP para outbox ID "
                            + message.getId() + ": " + e.getMessage(),
                    e);
        }

        return true;
    }

    private void validarPayload(Outbox message, OutboxAttemptSnapshot snapshot) {
        if (message.getVersion() < 0) {
            throw new OutboxPublishException(
                    snapshot,
                    OutboxFailureCategory.POISON_PILL,
                    "INVALID_VERSION",
                    "Versão negativa inválida na outbox ID " + message.getId() + ": " + message.getVersion());
        }
        if (message.getEventType() == null || message.getEventType().isBlank()) {
            throw new OutboxPublishException(
                    snapshot,
                    OutboxFailureCategory.POISON_PILL,
                    "INVALID_EVENT_TYPE",
                    "EventType ausente na outbox ID " + message.getId());
        }
        if (message.getPayload() == null) {
            throw new OutboxPublishException(
                    snapshot,
                    OutboxFailureCategory.POISON_PILL,
                    "INVALID_PAYLOAD",
                    "Payload nulo na outbox ID " + message.getId());
        }

        Class<?> clazz;
        try {
            clazz = Class.forName(message.getEventType());
        } catch (ClassNotFoundException e) {
            throw new OutboxPublishException(
                    snapshot,
                    OutboxFailureCategory.POISON_PILL,
                    "CLASS_NOT_FOUND",
                    "EventType não encontrado (" + message.getEventType() + "): " + e.getMessage(),
                    e);
        } catch (LinkageError e) {
            throw new OutboxPublishException(
                    snapshot,
                    OutboxFailureCategory.POISON_PILL,
                    "CLASS_LOAD_ERROR",
                    "Erro ao carregar eventType (" + message.getEventType() + "): " + e.getMessage(),
                    e);
        }

        try {
            objectMapper.readValue(message.getPayload(), clazz);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new OutboxPublishException(
                    snapshot,
                    OutboxFailureCategory.POISON_PILL,
                    "INVALID_PAYLOAD",
                    "Payload JSON inválido para " + message.getEventType() + ": " + e.getMessage(),
                    e);
        }
    }

    private OutboxAttemptSnapshot enviarEAguardarConfirmacao(
            Outbox message,
            OutboxAttemptSnapshot baseSnapshot,
            AtomicReference<OutboxAttemptSnapshot> attemptRef) {
        String messageId = "outbox-" + message.getId();
        String correlationId = messageId + "-" + UUID.randomUUID();
        OutboxAttemptSnapshot snapshot = baseSnapshot.withDispatchIds(messageId, correlationId);
        attemptRef.set(snapshot);

        MessageProperties properties = MessagePropertiesBuilder.newInstance()
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(messageId)
                .setCorrelationId(correlationId)
                .setHeader("x-outbox-id", message.getId())
                .setHeader("x-event-version", message.getVersion())
                .build();

        Message amqpMessage = new Message(message.getPayload().getBytes(StandardCharsets.UTF_8), properties);
        CorrelationData correlationData = new CorrelationData(correlationId);

        CorrelationData.Confirm confirm;
        try {
            rabbitTemplate.send(message.getExchange(), message.getRoutingKey(), amqpMessage, correlationData);
            confirm = correlationData.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OutboxPublishException(
                    snapshot,
                    OutboxFailureCategory.BROKER_UNAVAILABLE,
                    "AMQP_CONFIRM_INTERRUPTED",
                    "Thread interrompida aguardando confirmação AMQP do outbox ID " + message.getId(),
                    e);
        } catch (TimeoutException e) {
            throw new OutboxPublishException(
                    snapshot,
                    OutboxFailureCategory.BROKER_UNAVAILABLE,
                    "AMQP_CONFIRM_TIMEOUT",
                    "Timeout (" + confirmTimeoutMs + " ms) aguardando confirmação AMQP do outbox ID " + message.getId(),
                    e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new OutboxPublishException(
                    snapshot,
                    OutboxFailureCategory.BROKER_UNAVAILABLE,
                    "AMQP_CONFIRM_ERROR",
                    "Erro na confirmação AMQP do outbox ID " + message.getId() + ": " + cause.getMessage(),
                    cause);
        } catch (AmqpConnectException e) {
            throw new OutboxPublishException(
                    snapshot,
                    OutboxFailureCategory.BROKER_UNAVAILABLE,
                    "AMQP_CONNECT_ERROR",
                    "Falha de conexão AMQP do outbox ID " + message.getId() + ": " + e.getMessage(),
                    e);
        } catch (AmqpException e) {
            throw new OutboxPublishException(
                    snapshot,
                    OutboxFailureCategory.BROKER_UNAVAILABLE,
                    "AMQP_ERROR",
                    "Erro AMQP do outbox ID " + message.getId() + ": " + e.getMessage(),
                    e);
        }

        if (confirm == null) {
            throw new OutboxPublishException(
                    snapshot,
                    OutboxFailureCategory.BROKER_UNAVAILABLE,
                    "AMQP_CONFIRM_NULL",
                    "Confirmação AMQP nula para outbox ID " + message.getId());
        }

        if (!confirm.isAck()) {
            String reason = confirm.getReason();
            if (isNotFoundReason(reason)) {
                throw new OutboxPublishException(
                        snapshot,
                        OutboxFailureCategory.ROUTING_OR_REJECTION,
                        "AMQP_404_NOT_FOUND",
                        String.format(
                                "Destino não encontrado (404) para outbox ID %d (exchange=%s, routingKey=%s, reason=%s)",
                                message.getId(), message.getExchange(), message.getRoutingKey(), reason));
            }
            throw new OutboxPublishException(
                    snapshot,
                    OutboxFailureCategory.BROKER_UNAVAILABLE,
                    "AMQP_NACK",
                    String.format(
                            "Broker rejeitou publicação do outbox ID %d (exchange=%s, routingKey=%s, reason=%s)",
                            message.getId(), message.getExchange(), message.getRoutingKey(), reason));
        }

        ReturnedMessage returned = correlationData.getReturned();
        if (returned != null) {
            throw new OutboxPublishException(
                    snapshot,
                    OutboxFailureCategory.ROUTING_OR_REJECTION,
                    "NO_ROUTE",
                    String.format(
                            "Mensagem sem rota (NO_ROUTE) para outbox ID %d (exchange=%s, routingKey=%s, replyCode=%d, replyText=%s)",
                            message.getId(),
                            message.getExchange(),
                            message.getRoutingKey(),
                            returned.getReplyCode(),
                            returned.getReplyText()));
        }
        return snapshot;
    }

    private static boolean isNotFoundReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        String upper = reason.toUpperCase(Locale.ROOT);
        return upper.contains("404") || upper.contains("NOT_FOUND");
    }
}
