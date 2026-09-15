package inv.checkout.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import common.exception.BusinessException;
import inv.shared.config.RabbitMQConfig;
import inv.checkout.infrastructure.messaging.event.PagamentoConfirmadoEvent;
import inv.shared.outbox.model.Outbox;
import inv.shared.outbox.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;

@Component
@Slf4j
@RequiredArgsConstructor
public class PagamentoListener {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onPagamentoConfirmado(PagamentoConfirmadoEvent event) {
        log.info("💳 Pagamento confirmado (Venda ID: {}). Persistindo no Outbox na mesma transação...", event.vendaId());

        try {
            Outbox outbox = new Outbox();
            outbox.setExchange(RabbitMQConfig.PDV_EXCHANGE);
            outbox.setRoutingKey(RabbitMQConfig.PDV_ROUTING_KEY);
            outbox.setEventType(event.getClass().getName());
            outbox.setPayload(objectMapper.writeValueAsString(event));
            outbox.setCreatedAt(LocalDateTime.now());

            outboxRepository.save(outbox);

        } catch (Exception e) {
            log.error("🚨 CRÍTICO: Falha ao registrar evento no Outbox. O rollback do pagamento será acionado.", e);
            throw new BusinessException("Falha interna ao processar registro de pagamento no Outbox.");
        }
    }
}