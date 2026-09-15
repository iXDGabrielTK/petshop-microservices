package inv.checkout.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import common.exception.BusinessException;
import inv.checkout.infrastructure.messaging.event.PagamentoConfirmadoEvent;
import inv.checkout.infrastructure.messaging.event.VendaAtualizadaEvent;
import inv.shared.config.RabbitMQConfig;
import inv.shared.event.VendaPagaEvent;
import inv.checkout.infrastructure.web.dto.VendaStatusResponse;
import inv.checkout.domain.model.Pagamento;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.inventory.usecase.EstoqueService;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class VendaEventHandler {

    private final VendaRepository vendaRepository;
    private final EntityManager entityManager;
    private final EstoqueService estoqueService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_PAGAMENTO_CONFIRMADO)
    @Retryable(
            retryFor = { OptimisticLockingFailureException.class },
            backoff = @Backoff(delay = 50, multiplier = 2.0, maxDelay = 1000, random = true)
    )
    @Transactional
    public void handlePagamentoConfirmado(String payload) {
        try {
            PagamentoConfirmadoEvent event = objectMapper.readValue(payload, PagamentoConfirmadoEvent.class);

            Venda venda = vendaRepository.findById(event.vendaId())
                    .orElseThrow(() -> new BusinessException("Venda não encontrada"));

            Pagamento pagamentoProxy = entityManager.getReference(Pagamento.class, event.pagamentoId());

            venda.registrarPagamentoConfirmado(pagamentoProxy);

            if (venda.estaPaga()) {
                venda.concluir();
                venda.getItens().forEach(item ->
                        estoqueService.confirmarBaixaEstoque(item.getProduto(), item.getQuantidade(), venda.getId())
                );
            }

            vendaRepository.save(venda);

            eventPublisher.publishEvent(new VendaPagaEvent(
                    venda.getId(),
                    pagamentoProxy.getUuidReferencia(),
                    pagamentoProxy.getValorRecebido()
            ));

            eventPublisher.publishEvent(new VendaAtualizadaEvent(venda.getId(), VendaStatusResponse.from(venda)));

        } catch (DataIntegrityViolationException e) {
            Throwable rootCause = NestedExceptionUtils.getRootCause(e);

            if (rootCause instanceof ConstraintViolationException cause) {
                String constraintName = cause.getConstraintName();
                if ("uk_pagamento_uuid".equals(constraintName) || "uk_lancamento_referencia".equals(constraintName)) {
                    log.info("Webhook duplicado ignorado com segurança (Constraint: {}).", constraintName);
                    return;
                }
            }
            log.error("Erro de integridade relacional não mapeado.", e);
            throw e;
        } catch (Exception e) {
            log.error("Erro ao processar evento. Voltará para a fila.", e);
            throw new RuntimeException(e);
        }
    }
}