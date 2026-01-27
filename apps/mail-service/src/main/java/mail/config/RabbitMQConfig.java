package mail.config;

import org.jspecify.annotations.NonNull;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ErrorHandler;
import org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGER_NAME = "auth.v1.events";
    public static final String ROUTING_KEY = "auth.password.reset";
    public static final String QUEUE_NAME = "auth.v1.password-reset.send-email";

    public static final String DLQ_EXCHANGE_NAME = "auth.v1.events.dlx";
    public static final String DLQ_QUEUE_NAME = "auth.v1.password-reset.send-email.dlq";
    public static final String DLQ_ROUTING_KEY = "auth.password.reset.dlq";

    public static final String INVENTORY_EXCHANGE = "inventory.v1.events";
    public static final String LOW_STOCK_QUEUE = "inventory.v1.low-stock.send-email";
    public static final String LOW_STOCK_ROUTING_KEY = "inventory.stock.low";

    @Bean
    public Queue dlqQueue() {
        return QueueBuilder.durable(DLQ_QUEUE_NAME).build();
    }

    @Bean
    public TopicExchange dlqExchange() {
        return new TopicExchange(DLQ_EXCHANGE_NAME);
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(dlqQueue()).to(dlqExchange()).with(DLQ_ROUTING_KEY);
    }

    @Bean
    public TopicExchange authExchange() {
        return new TopicExchange(EXCHANGER_NAME);
    }

    @Bean
    public TopicExchange inventoryExchange() {
        return new TopicExchange(INVENTORY_EXCHANGE);
    }

    @Bean
    public Queue lowStockQueue() {
        return QueueBuilder.durable(LOW_STOCK_QUEUE).build();
    }

    @Bean
    public Binding lowStockBinding() {
        return BindingBuilder.bind(lowStockQueue())
                .to(inventoryExchange())
                .with(LOW_STOCK_ROUTING_KEY);
    }

    @Bean
    public Queue passwordResetQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .withArgument("x-dead-letter-exchange", DLQ_EXCHANGE_NAME)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding binding() {
        return BindingBuilder.bind(passwordResetQueue()).to(authExchange()).with(ROUTING_KEY);
    }

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // Configuração do listener com tratamento de erros personalizado
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);

        factory.setErrorHandler(errorHandler());

        return factory;
    }

    @Bean
    public ErrorHandler errorHandler() {
        return new ConditionalRejectingErrorHandler(new MyFatalExceptionStrategy());
    }

    // Classe personalizada para definir quais exceções são fatais
    public static class MyFatalExceptionStrategy extends ConditionalRejectingErrorHandler.DefaultExceptionStrategy {

        @Override
        public boolean isFatal(@NonNull Throwable t) {
            if (super.isFatal(t)) {
                return true;
            }

            Throwable causaReal = t.getCause();

            if (t instanceof ListenerExecutionFailedException && causaReal != null) {

                if (causaReal instanceof IllegalArgumentException) {
                    logger.error("Erro Fatal no Listener (IllegalArgumentException): " + causaReal.getMessage());
                    return true;
                }

                if (causaReal instanceof NullPointerException) {
                    logger.error("Erro Fatal no Listener (NullPointerException): " + causaReal.getMessage());
                    return true;
                }

            }

            logger.error("Erro Temporário no Listener: " + t.getMessage());
            return false;
        }
    }
}