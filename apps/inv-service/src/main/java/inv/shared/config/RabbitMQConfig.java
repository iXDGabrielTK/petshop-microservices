/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQConfig.class);

    public static final String EXCHANGE_NAME = "inventory.v1.events";
    public static final String ROUTING_KEY_LOW_STOCK = "inventory.stock.low";
    public static final String QUEUE_LOW_STOCK = "inventory.v1.stock-low.notify";

    public static final String DLQ_EXCHANGE_INVENTORY = "inventory.v1.events.dlx";
    public static final String DLQ_QUEUE_LOW_STOCK = "inventory.v1.stock-low.notify.dlq";
    public static final String DLQ_ROUTING_KEY_LOW_STOCK = "inventory.stock.low.dlq";


    public static final String PDV_EXCHANGE = "pdv.exchange";
    public static final String PDV_ROUTING_KEY = "venda.pagamento.confirmado";
    public static final String QUEUE_PAGAMENTO_CONFIRMADO = "pdv.venda.pagamento.confirmado.queue";

    public static final String DLQ_EXCHANGE_PDV = "pdv.exchange.dlx";
    public static final String DLQ_QUEUE_PAGAMENTO_CONFIRMADO = "pdv.venda.pagamento.confirmado.queue.dlq";
    public static final String DLQ_ROUTING_KEY_PAGAMENTO_CONFIRMADO = "venda.pagamento.confirmado.dlq";

    public static final String ROUTING_KEY_VENDA_ESTORNADA = "venda.estornada";
    public static final String ROUTING_KEY_VENDA_ESTORNADA_DLQ = "venda.estornada.dlq";
    public static final String QUEUE_VENDA_ESTORNADA = "pdv.venda.estornada.queue";
    public static final String QUEUE_VENDA_ESTORNADA_DLQ = "pdv.venda.estornada.queue.dlq";

    @Bean
    public TopicExchange inventoryExchange() {
        return new TopicExchange(EXCHANGE_NAME);
    }

    @Bean
    public TopicExchange pdvExchange() {
        return new TopicExchange(PDV_EXCHANGE);
    }

    @Bean
    @ConditionalOnProperty(
            name = "inv.rabbitmq.legacy-low-stock-queue.enabled",
            havingValue = "true",
            matchIfMissing = false)
    public TopicExchange dlqExchangeInventory() {
        return new TopicExchange(DLQ_EXCHANGE_INVENTORY);
    }

    @Bean
    public TopicExchange dlqExchangePdv() {
        return new TopicExchange(DLQ_EXCHANGE_PDV);
    }


    @Bean
    @ConditionalOnProperty(
            name = "inv.rabbitmq.legacy-low-stock-queue.enabled",
            havingValue = "true",
            matchIfMissing = false)
    public Queue lowStockQueue() {
        return QueueBuilder.durable(QUEUE_LOW_STOCK)
                .withArgument("x-dead-letter-exchange", DLQ_EXCHANGE_INVENTORY)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY_LOW_STOCK)
                .build();
    }

    @Bean
    public Queue pagamentoConfirmadoQueue() {
        return QueueBuilder.durable(QUEUE_PAGAMENTO_CONFIRMADO)
                .withArgument("x-dead-letter-exchange", DLQ_EXCHANGE_PDV)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY_PAGAMENTO_CONFIRMADO)
                .build();
    }

    @Bean
    @ConditionalOnProperty(
            name = "inv.rabbitmq.legacy-low-stock-queue.enabled",
            havingValue = "true",
            matchIfMissing = false)
    public Queue dlqQueueLowStock() {
        return QueueBuilder.durable(DLQ_QUEUE_LOW_STOCK).build();
    }

    @Bean
    public Queue dlqQueuePagamentoConfirmado() {
        return QueueBuilder.durable(DLQ_QUEUE_PAGAMENTO_CONFIRMADO).build();
    }

    @Bean
    public Queue vendaEstornadaQueue() {
        return QueueBuilder.durable(QUEUE_VENDA_ESTORNADA)
                .withArgument("x-dead-letter-exchange", DLQ_EXCHANGE_PDV)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY_VENDA_ESTORNADA_DLQ)
                .build();
    }

    @Bean
    public Queue dlqQueueVendaEstornada() {
        return QueueBuilder.durable(QUEUE_VENDA_ESTORNADA_DLQ).build();
    }

    @Bean
    @ConditionalOnProperty(
            name = "inv.rabbitmq.legacy-low-stock-queue.enabled",
            havingValue = "true",
            matchIfMissing = false)
    public Binding bindingLowStock() {
        return BindingBuilder.bind(lowStockQueue()).to(inventoryExchange()).with(ROUTING_KEY_LOW_STOCK);
    }

    @Bean
    public Binding bindingPagamentoConfirmado() {
        return BindingBuilder.bind(pagamentoConfirmadoQueue()).to(pdvExchange()).with(PDV_ROUTING_KEY);
    }

    @Bean
    public Binding bindingVendaEstornada() {
        return BindingBuilder.bind(vendaEstornadaQueue()).to(pdvExchange()).with(ROUTING_KEY_VENDA_ESTORNADA);
    }

    @Bean
    @ConditionalOnProperty(
            name = "inv.rabbitmq.legacy-low-stock-queue.enabled",
            havingValue = "true",
            matchIfMissing = false)
    public Binding dlqBindingLowStock() {
        return BindingBuilder.bind(dlqQueueLowStock()).to(dlqExchangeInventory()).with(DLQ_ROUTING_KEY_LOW_STOCK);
    }

    @Bean
    public Binding dlqBindingPagamentoConfirmado() {
        return BindingBuilder.bind(dlqQueuePagamentoConfirmado()).to(dlqExchangePdv()).with(DLQ_ROUTING_KEY_PAGAMENTO_CONFIRMADO);
    }

    @Bean
    public Binding dlqBindingVendaEstornada() {
        return BindingBuilder.bind(dlqQueueVendaEstornada()).to(dlqExchangePdv()).with(ROUTING_KEY_VENDA_ESTORNADA_DLQ);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplateCustomizer outboxReturnsCallbackCustomizer() {
        return rabbitTemplate -> rabbitTemplate.setReturnsCallback(returned -> {
            MessageProperties props = returned.getMessage() != null
                    ? returned.getMessage().getMessageProperties()
                    : null;
            String messageId = props != null ? props.getMessageId() : null;
            Object outboxId = props != null ? props.getHeader("x-outbox-id") : null;
            log.warn(
                    "Mensagem retornada pelo broker (NO_ROUTE): replyCode={}, replyText={}, exchange={}, routingKey={}, messageId={}, x-outbox-id={}",
                    returned.getReplyCode(),
                    returned.getReplyText(),
                    returned.getExchange(),
                    returned.getRoutingKey(),
                    messageId,
                    outboxId);
        });
    }
}
