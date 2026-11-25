/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
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
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Consumidor AMQP de estorno contábil no Bounded Context de Finance.
 * Garante idempotência estrita via chave determinística uuidReferenciaEstorno e
 * emite LancamentoRegistradoEvent para alimentar as projeções financeiras CQRS.
 * A pendência de projeção é persistida na mesma transação do lançamento, permitindo a retomada automática
 * pelo ProjectionRetryScheduler caso a execução assíncrona pós-commit se perca.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VendaEstornadaEventListener {

    private static final int MAX_PAYLOAD_LOG = 256;

    private final LancamentoFinanceiroRepository lancamentoRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final RegistroPendenciaProjecao registroPendencia;

    @Transactional
    @RabbitListener(queues = RabbitMQConfig.QUEUE_VENDA_ESTORNADA)
    public void handleVendaEstornada(Message message) {
        if (message == null) {
            log.warn("Mensagem nula recebida na fila de estorno. Ignorando.");
            return;
        }

        byte[] body = message.getBody();
        if (body == null || body.length == 0) {
            log.error("Payload vazio recebido para VendaEstornadaEvent. Mensagem enviada para DLQ.");
            throw new AmqpRejectAndDontRequeueException("Payload inválido para VendaEstornadaEvent (vazio)");
        }

        VendaEstornadaEvent event;
        try {
            event = objectMapper.readValue(body, VendaEstornadaEvent.class);
        } catch (JsonProcessingException e) {
            log.error("Erro ao desserializar JSON de VendaEstornadaEvent ({}): {}. Mensagem enviada para DLQ.",
                    e.getClass().getSimpleName(), trechoParaLog(new String(body, StandardCharsets.UTF_8)));
            throw rejeicaoDePayload(e);
        } catch (Exception e) {
            log.error("Erro ao desserializar Message para VendaEstornadaEvent ({}). Mensagem enviada para DLQ.",
                    e.getClass().getSimpleName());
            throw rejeicaoDePayload(e);
        }

        processar(event);
    }

    private static String trechoParaLog(String payload) {
        int fim = Math.min(MAX_PAYLOAD_LOG, payload.length());
        if (fim > 0 && Character.isHighSurrogate(payload.charAt(fim - 1))) {
            fim--;
        }
        return payload.substring(0, fim).replace('\r', ' ').replace('\n', ' ');
    }

    private static AmqpRejectAndDontRequeueException rejeicaoDePayload(Exception erro) {
        // O contêiner também registra a rejeição. A mensagem do parser pode incluir o payload bruto.
        return new AmqpRejectAndDontRequeueException("Payload inválido para VendaEstornadaEvent (" +
                erro.getClass().getSimpleName() + ")");
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
            LancamentoRegistradoEvent lancamentoRegistrado = new LancamentoRegistradoEvent(
                    idOpt.get(),
                    event.vendaId(),
                    valorEstornoNegativo,
                    TipoLancamento.ESTORNO
            );
            registroPendencia.registrarNaTransacaoDoLedger(lancamentoRegistrado);
            eventPublisher.publishEvent(lancamentoRegistrado);
            log.info("Lançamento de estorno {} gerado com sucesso para venda {} com valor {}",
                    idOpt.get(), event.vendaId(), valorEstornoNegativo);
        } else {
            log.info("Lançamento de estorno com referência {} já inserido concorrentemente para venda {}. Evento não emitido.",
                    event.uuidReferenciaEstorno(), event.vendaId());
        }
    }
}
