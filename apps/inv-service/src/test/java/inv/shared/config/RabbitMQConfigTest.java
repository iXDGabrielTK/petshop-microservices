package inv.shared.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RabbitMQConfig — Testes Unitários de Topologia AMQP e Dead-Lettering")
class RabbitMQConfigTest {

    private final RabbitMQConfig rabbitMQConfig = new RabbitMQConfig();

    @Test
    @DisplayName("DEVE configurar fila durável de venda estornada com DLX e routing key de DLQ")
    void deveConfigurarFilaVendaEstornadaComDlxEDlqRoutingKey() {
        Queue queue = rabbitMQConfig.vendaEstornadaQueue();

        assertThat(queue).isNotNull();
        assertThat(queue.getName()).isEqualTo(RabbitMQConfig.QUEUE_VENDA_ESTORNADA);
        assertThat(queue.getName()).isEqualTo("pdv.venda.estornada.queue");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", RabbitMQConfig.DLQ_EXCHANGE_PDV)
                .containsEntry("x-dead-letter-exchange", "pdv.exchange.dlx")
                .containsEntry("x-dead-letter-routing-key", RabbitMQConfig.ROUTING_KEY_VENDA_ESTORNADA_DLQ)
                .containsEntry("x-dead-letter-routing-key", "venda.estornada.dlq");
    }

    @Test
    @DisplayName("DEVE configurar fila durável da DLQ de venda estornada com nome canônico")
    void deveConfigurarDlqQueueVendaEstornada() {
        Queue dlqQueue = rabbitMQConfig.dlqQueueVendaEstornada();

        assertThat(dlqQueue).isNotNull();
        assertThat(dlqQueue.getName()).isEqualTo(RabbitMQConfig.QUEUE_VENDA_ESTORNADA_DLQ);
        assertThat(dlqQueue.getName()).isEqualTo("pdv.venda.estornada.queue.dlq");
        assertThat(dlqQueue.isDurable()).isTrue();
    }

    @Test
    @DisplayName("DEVE configurar binding da fila de venda estornada na exchange principal do PDV")
    void deveConfigurarBindingVendaEstornada() {
        Binding binding = rabbitMQConfig.bindingVendaEstornada();

        assertThat(binding).isNotNull();
        assertThat(binding.getDestination()).isEqualTo(RabbitMQConfig.QUEUE_VENDA_ESTORNADA);
        assertThat(binding.getExchange()).isEqualTo(RabbitMQConfig.PDV_EXCHANGE);
        assertThat(binding.getExchange()).isEqualTo("pdv.exchange");
        assertThat(binding.getRoutingKey()).isEqualTo(RabbitMQConfig.ROUTING_KEY_VENDA_ESTORNADA);
        assertThat(binding.getRoutingKey()).isEqualTo("venda.estornada");
    }

    @Test
    @DisplayName("DEVE configurar binding da DLQ de venda estornada na exchange DLX do PDV")
    void deveConfigurarDlqBindingVendaEstornada() {
        Binding dlqBinding = rabbitMQConfig.dlqBindingVendaEstornada();

        assertThat(dlqBinding).isNotNull();
        assertThat(dlqBinding.getDestination()).isEqualTo(RabbitMQConfig.QUEUE_VENDA_ESTORNADA_DLQ);
        assertThat(dlqBinding.getExchange()).isEqualTo(RabbitMQConfig.DLQ_EXCHANGE_PDV);
        assertThat(dlqBinding.getExchange()).isEqualTo("pdv.exchange.dlx");
        assertThat(dlqBinding.getRoutingKey()).isEqualTo(RabbitMQConfig.ROUTING_KEY_VENDA_ESTORNADA_DLQ);
        assertThat(dlqBinding.getRoutingKey()).isEqualTo("venda.estornada.dlq");
    }

    @Test
    @DisplayName("DEVE configurar TopicExchanges principais e de DLX do ecossistema")
    void deveConfigurarTopicExchanges() {
        TopicExchange pdvExchange = rabbitMQConfig.pdvExchange();
        assertThat(pdvExchange.getName()).isEqualTo(RabbitMQConfig.PDV_EXCHANGE);

        TopicExchange dlqExchangePdv = rabbitMQConfig.dlqExchangePdv();
        assertThat(dlqExchangePdv.getName()).isEqualTo(RabbitMQConfig.DLQ_EXCHANGE_PDV);

        TopicExchange inventoryExchange = rabbitMQConfig.inventoryExchange();
        assertThat(inventoryExchange.getName()).isEqualTo(RabbitMQConfig.EXCHANGE_NAME);

        TopicExchange dlqExchangeInventory = rabbitMQConfig.dlqExchangeInventory();
        assertThat(dlqExchangeInventory.getName()).isEqualTo(RabbitMQConfig.DLQ_EXCHANGE_INVENTORY);
    }

    @Test
    @DisplayName("DEVE configurar filas e bindings legados de estoque baixo e pagamento confirmado")
    void deveConfigurarFilasEBindingsPreexistentes() {
        Queue lowStock = rabbitMQConfig.lowStockQueue();
        assertThat(lowStock.getName()).isEqualTo(RabbitMQConfig.QUEUE_LOW_STOCK);
        assertThat(lowStock.getArguments()).containsEntry("x-dead-letter-exchange", RabbitMQConfig.DLQ_EXCHANGE_INVENTORY);

        Queue dlqLowStock = rabbitMQConfig.dlqQueueLowStock();
        assertThat(dlqLowStock.getName()).isEqualTo(RabbitMQConfig.DLQ_QUEUE_LOW_STOCK);

        Binding bindingLowStock = rabbitMQConfig.bindingLowStock();
        assertThat(bindingLowStock.getDestination()).isEqualTo(RabbitMQConfig.QUEUE_LOW_STOCK);
        assertThat(bindingLowStock.getExchange()).isEqualTo(RabbitMQConfig.EXCHANGE_NAME);

        Binding dlqBindingLowStock = rabbitMQConfig.dlqBindingLowStock();
        assertThat(dlqBindingLowStock.getDestination()).isEqualTo(RabbitMQConfig.DLQ_QUEUE_LOW_STOCK);
        assertThat(dlqBindingLowStock.getExchange()).isEqualTo(RabbitMQConfig.DLQ_EXCHANGE_INVENTORY);

        Queue pagConfirmado = rabbitMQConfig.pagamentoConfirmadoQueue();
        assertThat(pagConfirmado.getName()).isEqualTo(RabbitMQConfig.QUEUE_PAGAMENTO_CONFIRMADO);
        assertThat(pagConfirmado.getArguments()).containsEntry("x-dead-letter-exchange", RabbitMQConfig.DLQ_EXCHANGE_PDV);

        Queue dlqPagConfirmado = rabbitMQConfig.dlqQueuePagamentoConfirmado();
        assertThat(dlqPagConfirmado.getName()).isEqualTo(RabbitMQConfig.DLQ_QUEUE_PAGAMENTO_CONFIRMADO);

        Binding bindingPag = rabbitMQConfig.bindingPagamentoConfirmado();
        assertThat(bindingPag.getDestination()).isEqualTo(RabbitMQConfig.QUEUE_PAGAMENTO_CONFIRMADO);
        assertThat(bindingPag.getExchange()).isEqualTo(RabbitMQConfig.PDV_EXCHANGE);

        Binding dlqBindingPag = rabbitMQConfig.dlqBindingPagamentoConfirmado();
        assertThat(dlqBindingPag.getDestination()).isEqualTo(RabbitMQConfig.DLQ_QUEUE_PAGAMENTO_CONFIRMADO);
        assertThat(dlqBindingPag.getExchange()).isEqualTo(RabbitMQConfig.DLQ_EXCHANGE_PDV);
    }

    @Test
    @DisplayName("DEVE fornecer Jackson2JsonMessageConverter como bean MessageConverter")
    void deveFornecerJackson2JsonMessageConverter() {
        MessageConverter converter = rabbitMQConfig.jsonMessageConverter();
        assertThat(converter).isNotNull().isInstanceOf(Jackson2JsonMessageConverter.class);
    }
}
