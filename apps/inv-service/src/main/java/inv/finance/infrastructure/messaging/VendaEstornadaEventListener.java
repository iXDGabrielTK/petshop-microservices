package inv.finance.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import inv.finance.domain.model.TipoLancamento;
import inv.finance.infrastructure.persistence.LancamentoFinanceiroRepository;
import inv.shared.config.RabbitMQConfig;
import inv.shared.event.VendaEstornadaEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Consumidor AMQP de estorno contábil no Bounded Context de Finance.
 * Garante idempotência estrita via chave determinística uuidReferenciaEstorno e
 * emite LancamentoRegistradoEvent para alimentar as projeções financeiras CQRS.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VendaEstornadaEventListener {

    private final LancamentoFinanceiroRepository lancamentoRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Transactional
    @RabbitListener(queues = RabbitMQConfig.QUEUE_VENDA_ESTORNADA)
    public void handleVendaEstornada(Object message) {
        if (message == null) {
            log.warn("Mensagem nula recebida na fila de estorno. Ignorando.");
            return;
        }

        VendaEstornadaEvent event;
        if (message instanceof VendaEstornadaEvent e) {
            event = e;
        } else if (message instanceof Message m) {
            try {
                event = objectMapper.readValue(m.getBody(), VendaEstornadaEvent.class);
            } catch (Exception e) {
                log.error("Erro não-retentável ao desserializar payload de Message para VendaEstornadaEvent. Mensagem enviada para DLQ.", e);
                throw new AmqpRejectAndDontRequeueException(e);
            }
        } else if (message instanceof String s) {
            try {
                event = objectMapper.readValue(s, VendaEstornadaEvent.class);
            } catch (JsonProcessingException e) {
                log.error("Erro não-retentável ao desserializar JSON de VendaEstornadaEvent: {}. Mensagem enviada para DLQ.", s, e);
                throw new AmqpRejectAndDontRequeueException(e);
            }
        } else if (message instanceof byte[] b) {
            try {
                event = objectMapper.readValue(b, VendaEstornadaEvent.class);
            } catch (Exception e) {
                log.error("Erro não-retentável ao desserializar bytes de VendaEstornadaEvent. Mensagem enviada para DLQ.", e);
                throw new AmqpRejectAndDontRequeueException(e);
            }
        } else {
            try {
                event = objectMapper.convertValue(message, VendaEstornadaEvent.class);
            } catch (Exception e) {
                log.error("Tipo de mensagem não suportado para VendaEstornadaEvent: {}. Mensagem enviada para DLQ.", message.getClass().getName(), e);
                throw new AmqpRejectAndDontRequeueException(e);
            }
        }

        processar(event);
    }

    private void processar(VendaEstornadaEvent event) {
        if (event == null || event.vendaId() == null || event.uuidReferenciaEstorno() == null || event.valorEstorno() == null) {
            log.warn("VendaEstornadaEvent inválido recebido (event={}). Ignorando processamento idempotente.", event);
            return;
        }

        if (lancamentoRepository.existsByReferencia(event.uuidReferenciaEstorno())) {
            log.info("Lançamento de estorno com referência {} já existe para a venda {}. Ignorando duplicidade idempotente.",
                    event.uuidReferenciaEstorno(), event.vendaId());
            return;
        }

        BigDecimal valorEstornoNegativo = event.valorEstorno().abs().negate();
        Optional<Long> idOpt = lancamentoRepository.inserirIdempotente(
                event.vendaId(),
                event.uuidReferenciaEstorno(),
                TipoLancamento.ESTORNO.name(),
                valorEstornoNegativo
        );

        if (idOpt.isPresent()) {
            eventPublisher.publishEvent(new LancamentoRegistradoEvent(
                    idOpt.get(),
                    event.vendaId(),
                    valorEstornoNegativo,
                    TipoLancamento.ESTORNO
            ));
            log.info("Lançamento de estorno {} gerado com sucesso para venda {} com valor {}",
                    idOpt.get(), event.vendaId(), valorEstornoNegativo);
        } else {
            log.info("Lançamento de estorno com referência {} já inserido concorrentemente para venda {}. Evento não emitido.",
                    event.uuidReferenciaEstorno(), event.vendaId());
        }
    }
}
