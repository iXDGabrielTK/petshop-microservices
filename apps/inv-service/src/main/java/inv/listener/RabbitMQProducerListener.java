package inv.listener;

import inv.config.RabbitMQConfig;
import inv.event.EstoqueAtingiuMinimoEvent;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class RabbitMQProducerListener {

    private final RabbitTemplate rabbitTemplate;

    public RabbitMQProducerListener(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEstoqueBaixo(EstoqueAtingiuMinimoEvent event) {
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE_NAME,
                RabbitMQConfig.ROUTING_KEY_LOW_STOCK,
                event.payload()
        );
    }
}