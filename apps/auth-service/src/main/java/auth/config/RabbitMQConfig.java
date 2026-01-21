package auth.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGER_NAME = "auth.v1.events";
    public static final String ROUTING_KEY = "auth.password.reset";
    public static final String QUEUE_NAME = "auth.v1.password-reset.send-email";

    public static final String DLQ_EXCHANGE_NAME = "auth.v1.events.dlx";
    public static final String DLQ_QUEUE_NAME = "auth.v1.password-reset.send-email.dlq";
    public static final String DLQ_ROUTING_KEY = "auth.password.reset.dlq";

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
}