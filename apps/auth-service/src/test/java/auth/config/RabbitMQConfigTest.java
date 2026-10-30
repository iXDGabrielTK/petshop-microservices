package auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RabbitMQConfig — Testes Unitários de Topologia e Filas de Mensageria")
class RabbitMQConfigTest {

    private final RabbitMQConfig rabbitMQConfig = new RabbitMQConfig();

    @Test
    @DisplayName("Deve configurar fila DLQ durável corretamente")
    void deveConfigurarDlqQueue() {
        Queue dlqQueue = rabbitMQConfig.dlqQueue();
        assertThat(dlqQueue).isNotNull();
        assertThat(dlqQueue.getName()).isEqualTo(RabbitMQConfig.DLQ_QUEUE_NAME);
        assertThat(dlqQueue.isDurable()).isTrue();
    }

    @Test
    @DisplayName("Deve configurar exchange DLQ como TopicExchange")
    void deveConfigurarDlqExchange() {
        TopicExchange dlqExchange = rabbitMQConfig.dlqExchange();
        assertThat(dlqExchange).isNotNull();
        assertThat(dlqExchange.getName()).isEqualTo(RabbitMQConfig.DLQ_EXCHANGE_NAME);
    }

    @Test
    @DisplayName("Deve configurar binding da DLQ")
    void deveConfigurarDlqBinding() {
        Binding dlqBinding = rabbitMQConfig.dlqBinding();
        assertThat(dlqBinding).isNotNull();
        assertThat(dlqBinding.getDestination()).isEqualTo(RabbitMQConfig.DLQ_QUEUE_NAME);
        assertThat(dlqBinding.getExchange()).isEqualTo(RabbitMQConfig.DLQ_EXCHANGE_NAME);
        assertThat(dlqBinding.getRoutingKey()).isEqualTo(RabbitMQConfig.DLQ_ROUTING_KEY);
    }

    @Test
    @DisplayName("Deve configurar exchange principal de eventos auth")
    void deveConfigurarAuthExchange() {
        TopicExchange exchange = rabbitMQConfig.authExchange();
        assertThat(exchange).isNotNull();
        assertThat(exchange.getName()).isEqualTo(RabbitMQConfig.EXCHANGER_NAME);
    }

    @Test
    @DisplayName("Deve configurar fila de password reset com dead letter exchange e routing key configurados")
    void deveConfigurarPasswordResetQueueComDlx() {
        Queue queue = rabbitMQConfig.passwordResetQueue();
        assertThat(queue).isNotNull();
        assertThat(queue.getName()).isEqualTo(RabbitMQConfig.QUEUE_NAME);
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments().get("x-dead-letter-exchange"))
                .isEqualTo(RabbitMQConfig.DLQ_EXCHANGE_NAME);
        assertThat(queue.getArguments().get("x-dead-letter-routing-key"))
                .isEqualTo(RabbitMQConfig.DLQ_ROUTING_KEY);
    }

    @Test
    @DisplayName("Deve configurar binding da fila principal com exchange e routing key")
    void deveConfigurarBindingPrincipal() {
        Binding binding = rabbitMQConfig.binding();
        assertThat(binding).isNotNull();
        assertThat(binding.getDestination()).isEqualTo(RabbitMQConfig.QUEUE_NAME);
        assertThat(binding.getExchange()).isEqualTo(RabbitMQConfig.EXCHANGER_NAME);
        assertThat(binding.getRoutingKey()).isEqualTo(RabbitMQConfig.ROUTING_KEY);
    }

    @Test
    @DisplayName("Deve fornecer Jackson2JsonMessageConverter como MessageConverter")
    void deveFornecerJackson2JsonMessageConverter() {
        MessageConverter converter = rabbitMQConfig.messageConverter();
        assertThat(converter).isInstanceOf(Jackson2JsonMessageConverter.class);
    }
}
