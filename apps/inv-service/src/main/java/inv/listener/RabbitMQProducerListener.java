package inv.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import inv.config.RabbitMQConfig;
import inv.event.EstoqueAtingiuMinimoEvent;
import inv.model.Outbox;
import inv.repository.OutboxRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class RabbitMQProducerListener {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public RabbitMQProducerListener(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void onEstoqueBaixo(EstoqueAtingiuMinimoEvent event) {
        try {
            String jsonPayload = objectMapper.writeValueAsString(event.payload());

            Outbox outbox = new Outbox();
            outbox.setExchange(RabbitMQConfig.EXCHANGE_NAME);
            outbox.setRoutingKey(RabbitMQConfig.ROUTING_KEY_LOW_STOCK);
            outbox.setPayload(jsonPayload);
            outbox.setEventType(event.payload().getClass().getName());
            outbox.setCreatedAt(LocalDateTime.now());

            outboxRepository.save(outbox);

        } catch (Exception e) {
            throw new RuntimeException("Erro ao persistir evento no Outbox", e);
        }
    }
}