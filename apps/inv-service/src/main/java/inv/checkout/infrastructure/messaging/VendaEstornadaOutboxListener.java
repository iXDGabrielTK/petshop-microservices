package inv.checkout.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import common.exception.BusinessException;
import inv.shared.config.RabbitMQConfig;
import inv.shared.event.VendaEstornadaEvent;
import inv.shared.outbox.model.Outbox;
import inv.shared.outbox.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Component
@Slf4j
@RequiredArgsConstructor
public class VendaEstornadaOutboxListener {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onVendaEstornada(VendaEstornadaEvent event) {
        if (event == null) {
            log.warn("⚠️ Evento VendaEstornadaEvent nulo recebido no OutboxListener. Ignorando.");
            return;
        }

        if (event.vendaId() == null || event.uuidReferenciaEstorno() == null || event.valorEstorno() == null || event.valorEstorno().compareTo(BigDecimal.ZERO) < 0) {
            log.error("🚨 CRÍTICO: Evento VendaEstornadaEvent inválido com campos nulos ou valor negativo (event={}). O rollback do cancelamento será acionado.", event);
            throw new BusinessException("Falha interna: evento de estorno possui campos obrigatórios nulos ou valor inválido.");
        }

        log.info("📢 Evento VendaEstornada capturado (Venda ID: {}). Persistindo no Outbox na mesma transação...", event.vendaId());

        try {
            Outbox outbox = new Outbox();
            outbox.setExchange(RabbitMQConfig.PDV_EXCHANGE);
            outbox.setRoutingKey(RabbitMQConfig.ROUTING_KEY_VENDA_ESTORNADA);
            outbox.setEventType(event.getClass().getName());
            outbox.setPayload(objectMapper.writeValueAsString(event));
            outbox.setVersion(1);
            outbox.setCreatedAt(LocalDateTime.now());

            outboxRepository.save(outbox);
            log.debug("✅ Evento VendaEstornada persistido no Outbox com sucesso.");

        } catch (Exception e) {
            log.error("🚨 CRÍTICO: Falha ao registrar evento de estorno no Outbox. O rollback do cancelamento será acionado.", e);
            throw new BusinessException("Falha interna ao processar registro de estorno no Outbox.");
        }
    }
}
