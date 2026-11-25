/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package mail.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RabbitMQConfig — Configuração de Filas, DLQs e Estratégia Fatal (mail-service)")
class RabbitMQConfigTest {

    private final RabbitMQConfig config = new RabbitMQConfig();

    @Test
    @DisplayName("lowStockQueue deve declarar argumentos de Dead Letter Exchange e Routing Key")
    void lowStockQueueDeveDefinirArgumentosDeDlq() {
        Queue queue = config.lowStockQueue();

        assertThat(queue.getName()).isEqualTo(RabbitMQConfig.LOW_STOCK_QUEUE);
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", RabbitMQConfig.LOW_STOCK_DLQ_EXCHANGE_NAME)
                .containsEntry("x-dead-letter-routing-key", RabbitMQConfig.LOW_STOCK_DLQ_ROUTING_KEY);

        Queue dlq = config.lowStockDlqQueue();
        TopicExchange dlqExchange = config.lowStockDlqExchange();
        Binding dlqBinding = config.lowStockDlqBinding();

        assertThat(dlq.getName()).isEqualTo(RabbitMQConfig.LOW_STOCK_DLQ_QUEUE_NAME);
        assertThat(dlq.isDurable()).isTrue();
        assertThat(dlqExchange.getName()).isEqualTo(RabbitMQConfig.LOW_STOCK_DLQ_EXCHANGE_NAME);
        assertThat(dlqBinding.getDestination()).isEqualTo(RabbitMQConfig.LOW_STOCK_DLQ_QUEUE_NAME);
        assertThat(dlqBinding.getExchange()).isEqualTo(RabbitMQConfig.LOW_STOCK_DLQ_EXCHANGE_NAME);
        assertThat(dlqBinding.getRoutingKey()).isEqualTo(RabbitMQConfig.LOW_STOCK_DLQ_ROUTING_KEY);
    }

    @Test
    @DisplayName("passwordResetQueue deve manter configuração de DLQ")
    void passwordResetQueueDeveDefinirArgumentosDeDlq() {
        Queue queue = config.passwordResetQueue();

        assertThat(queue.getName()).isEqualTo(RabbitMQConfig.QUEUE_NAME);
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", RabbitMQConfig.DLQ_EXCHANGE_NAME)
                .containsEntry("x-dead-letter-routing-key", RabbitMQConfig.DLQ_ROUTING_KEY);
    }

    @Test
    @DisplayName("MyFatalExceptionStrategy deve classificar IllegalArgumentException e NullPointerException como fatais")
    void fatalExceptionStrategyDeveClassificarExcecoesFataisETransitorias() {
        RabbitMQConfig.MyFatalExceptionStrategy strategy = new RabbitMQConfig.MyFatalExceptionStrategy();
        ReflectionTestUtils.setField(strategy, "logger", org.apache.commons.logging.LogFactory.getLog(RabbitMQConfig.class));

        ListenerExecutionFailedException fatalIae = new ListenerExecutionFailedException(
                "falha", new IllegalArgumentException("payload inválido"));
        ListenerExecutionFailedException fatalNpe = new ListenerExecutionFailedException(
                "falha", new NullPointerException("campo nulo"));
        ListenerExecutionFailedException transitoria = new ListenerExecutionFailedException(
                "falha", new IllegalStateException("smtp indisponível"));

        assertThat(strategy.isFatal(fatalIae)).isTrue();
        assertThat(strategy.isFatal(fatalNpe)).isTrue();
        assertThat(strategy.isFatal(transitoria)).isFalse();
    }
}
