package inv.shared.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

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

    @Bean
    public TopicExchange inventoryExchange() {
        return new TopicExchange(EXCHANGE_NAME);
    }

    @Bean
    public TopicExchange pdvExchange() {
        return new TopicExchange(PDV_EXCHANGE);
    }

    @Bean
    public TopicExchange dlqExchangeInventory() {
        return new TopicExchange(DLQ_EXCHANGE_INVENTORY);
    }

    @Bean
    public TopicExchange dlqExchangePdv() {
        return new TopicExchange(DLQ_EXCHANGE_PDV);
    }


    @Bean
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
    public Queue dlqQueueLowStock() {
        return QueueBuilder.durable(DLQ_QUEUE_LOW_STOCK).build();
    }

    @Bean
    public Queue dlqQueuePagamentoConfirmado() {
        return QueueBuilder.durable(DLQ_QUEUE_PAGAMENTO_CONFIRMADO).build();
    }

    @Bean
    public Binding bindingLowStock() {
        return BindingBuilder.bind(lowStockQueue()).to(inventoryExchange()).with(ROUTING_KEY_LOW_STOCK);
    }

    @Bean
    public Binding bindingPagamentoConfirmado() {
        return BindingBuilder.bind(pagamentoConfirmadoQueue()).to(pdvExchange()).with(PDV_ROUTING_KEY);
    }

    @Bean
    public Binding dlqBindingLowStock() {
        return BindingBuilder.bind(dlqQueueLowStock()).to(dlqExchangeInventory()).with(DLQ_ROUTING_KEY_LOW_STOCK);
    }

    @Bean
    public Binding dlqBindingPagamentoConfirmado() {
        return BindingBuilder.bind(dlqQueuePagamentoConfirmado()).to(dlqExchangePdv()).with(DLQ_ROUTING_KEY_PAGAMENTO_CONFIRMADO);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}