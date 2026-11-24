/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.outbox.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import inv.shared.outbox.model.Outbox;
import inv.shared.outbox.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final long confirmTimeoutMs;

    public OutboxProcessor(
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

    /**
     * Tenta processar a próxima mensagem da fila.
     * Retorna TRUE se processou algo, FALSE se a fila estava vazia.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processNext() {
        // 1. Busca atômica com SKIP LOCKED
        // Se outra instância pegou o registro 10, esta query vai pular pro 11 automaticamente.
        var messageOpt = outboxRepository.findNextMessageToProcess();

        // Se a mensagem já foi apagada por outro processo concorrente, apenas ignoramos
        if (messageOpt.isEmpty()) {
            return false;
        }

        Outbox message = messageOpt.get();

        try {
            // 1. Valida classe e payload sem importar Bounded Contexts (R12)
            Class<?> clazz = Class.forName(message.getEventType());
            objectMapper.readValue(message.getPayload(), clazz);

            // 2. Publica JSON UTF-8 persistente sem __TypeId__, com identidade estável e correlação por tentativa
            String messageId = "outbox-" + message.getId();
            String correlationId = messageId + "-" + UUID.randomUUID();

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

            // 3. Envia para o RabbitMQ (IO de rede) e aguarda confirmação + ausência de NO_ROUTE
            rabbitTemplate.send(message.getExchange(), message.getRoutingKey(), amqpMessage, correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);

            if (confirm == null || !confirm.isAck()) {
                String reason = confirm != null ? confirm.getReason() : "confirmacao nula";
                throw new OutboxPublishException(String.format(
                        "Broker rejeitou publicação do outbox ID %d (exchange=%s, routingKey=%s, reason=%s)",
                        message.getId(), message.getExchange(), message.getRoutingKey(), reason));
            }

            ReturnedMessage returned = correlationData.getReturned();
            if (returned != null) {
                throw new OutboxPublishException(String.format(
                        "Mensagem sem rota (NO_ROUTE) para outbox ID %d (exchange=%s, routingKey=%s, replyCode=%d, replyText=%s)",
                        message.getId(),
                        message.getExchange(),
                        message.getRoutingKey(),
                        returned.getReplyCode(),
                        returned.getReplyText()));
            }

            // 4. Deleta do Outbox somente após Confirm(ack=true) com returned == null
            outboxRepository.delete(message);

            return true;

        } catch (OutboxPublishException e) {
            log.error(
                    "Falha ao publicar outbox ID {} (exchange={}, routingKey={}): {}",
                    message.getId(),
                    message.getExchange(),
                    message.getRoutingKey(),
                    e.getMessage());
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error(
                    "Thread interrompida aguardando confirmação AMQP do outbox ID {} (exchange={}, routingKey={})",
                    message.getId(),
                    message.getExchange(),
                    message.getRoutingKey(),
                    e);
            throw new OutboxPublishException(
                    "Thread interrompida aguardando confirmação AMQP do outbox ID " + message.getId(), e);
        } catch (TimeoutException e) {
            log.error(
                    "Timeout ({} ms) aguardando confirmação AMQP do outbox ID {} (exchange={}, routingKey={})",
                    confirmTimeoutMs,
                    message.getId(),
                    message.getExchange(),
                    message.getRoutingKey(),
                    e);
            throw new OutboxPublishException(
                    "Timeout aguardando confirmação AMQP do outbox ID " + message.getId(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error(
                    "Erro na confirmação AMQP do outbox ID {} (exchange={}, routingKey={}): {}",
                    message.getId(),
                    message.getExchange(),
                    message.getRoutingKey(),
                    cause.getMessage(),
                    cause);
            throw new OutboxPublishException(
                    "Erro na confirmação AMQP do outbox ID " + message.getId(), cause);
        } catch (Exception e) {
            log.error(
                    "Erro ao processar outbox ID {} (exchange={}, routingKey={}): {}",
                    message.getId(),
                    message.getExchange(),
                    message.getRoutingKey(),
                    e.getMessage(),
                    e);
            throw new OutboxPublishException(
                    "Erro ao processar outbox ID " + message.getId(), e);
        }
    }
}
