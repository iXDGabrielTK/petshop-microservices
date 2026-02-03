package inv.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import common.exception.BusinessException;
import inv.config.RabbitMQConfig;
import inv.event.EstoqueAtingiuMinimoEvent;
import inv.model.Outbox;
import inv.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;

@Component
@Slf4j
@RequiredArgsConstructor
public class RabbitMQProducerListener {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Ouve o evento de domínio e persiste no Outbox NA MESMA TRANSAÇÃO da Venda.
     * * phase = BEFORE_COMMIT: Garante que o registro na tabela 'outbox'
     * seja comitado atomicamente junto com a venda e a movimentação de estoque.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onEstoqueBaixo(EstoqueAtingiuMinimoEvent event) {
        log.info("🔔 Evento capturado: Estoque baixo para '{}'. Persistindo no Outbox...",
                event.payload().nomeProduto());

        try {
            String jsonPayload = objectMapper.writeValueAsString(event.payload());

            Outbox outbox = new Outbox();
            outbox.setExchange(RabbitMQConfig.EXCHANGE_NAME);
            outbox.setRoutingKey(RabbitMQConfig.ROUTING_KEY_LOW_STOCK);
            outbox.setEventType(event.payload().getClass().getName());
            outbox.setPayload(jsonPayload);
            outbox.setVersion(event.payload().version());
            outbox.setCreatedAt(LocalDateTime.now());

            outboxRepository.save(outbox);

            log.debug("✅ Evento persistido no Outbox ID: {}", outbox.getId());

        } catch (JsonProcessingException e) {
            log.error("❌ Erro crítico ao serializar evento de estoque. Rollback será acionado.", e);

            throw new BusinessException("Falha interna ao processar alerta de estoque.");
        }
    }
}