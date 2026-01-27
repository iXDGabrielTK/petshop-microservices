package inv.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import inv.model.Outbox;
import inv.repository.OutboxRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.logging.Logger;

@Component
public class OutboxScheduler {

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    Logger logger = Logger.getLogger(OutboxScheduler.class.getName());

    public OutboxScheduler(OutboxRepository outboxRepository, RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void processOutbox() {
        // Busca todas as mensagens pendentes (em prod, ideal usar paginação)
        List<Outbox> pendingMessages = outboxRepository.findAll();

        if (pendingMessages.isEmpty()) {
            return;
        }

        for (Outbox message : pendingMessages) {
            try {
                // 1. Recupera a classe original pelo nome salvo
                Class<?> clazz = Class.forName(message.getEventType());

                // 2. Deserializa o JSON de volta para o Objeto Java
                Object payload = objectMapper.readValue(message.getPayload(), clazz);

                // 3. Envia para o RabbitMQ
                // O RabbitTemplate vai usar o Jackson2JsonMessageConverter configurado para serializar novamente para o broker
                rabbitTemplate.convertAndSend(
                        message.getExchange(),
                        message.getRoutingKey(),
                        payload
                );

                // 4. Se chegou aqui, o envio foi sucesso. Deletamos do Outbox.
                outboxRepository.delete(message);

            } catch (Exception e) {
                // A mensagem atual continua no banco e será tentada novamente no próximo ciclo
                logger.severe("Erro ao processar outbox ID " + message.getId() + ": " + e.getMessage());
            }
        }
    }
}