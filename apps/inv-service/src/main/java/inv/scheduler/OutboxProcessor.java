package inv.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import inv.model.Outbox;
import inv.repository.OutboxRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.logging.Logger;

@Component
public class OutboxProcessor {

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    Logger logger = Logger.getLogger(OutboxProcessor.class.getName());

    public OutboxProcessor(OutboxRepository outboxRepository, RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Tenta processar a próxima mensagem da fila.
     * Retorna TRUE se processou algo, FALSE se a fila estava vazia.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processNext() {
        // 1. Busca atômica com SKIP LOCKED
        // Se outra instância pegou o registro 10, esta query vai pular pro 11 automaticamente.
        var messageOpt = outboxRepository.findNextMessageToProcess();

        // Se a mensagem já foi apagada por outro processo concorrente, apenas ignoramos
        if (messageOpt.isEmpty()) {
            return false;
        }

        Outbox message = messageOpt.get();

        try {
            // 1. Recupera classe e payload
            Class<?> clazz = Class.forName(message.getEventType());
            Object payload = objectMapper.readValue(message.getPayload(), clazz);

            // 2. Envia para o RabbitMQ (IO de rede)
            // Nota: Se o Rabbit cair aqui, a transação do banco faz rollback e a mensagem não é deletada.
            rabbitTemplate.convertAndSend(
                    message.getExchange(),
                    message.getRoutingKey(),
                    payload
            );

            // 3. Deleta do Outbox (Confirmação)
            outboxRepository.delete(message);

            return true;

        } catch (Exception e) {
            logger.severe("Erro ao processar outbox ID " + message.getId() + ": " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
}