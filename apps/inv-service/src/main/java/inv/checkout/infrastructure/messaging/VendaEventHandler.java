package inv.checkout.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import common.exception.BusinessException;
import inv.checkout.domain.model.Pagamento;
import inv.checkout.domain.model.StatusPagamento;
import inv.checkout.domain.model.StatusVenda;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.messaging.event.PagamentoConfirmadoEvent;
import inv.checkout.infrastructure.messaging.event.VendaAtualizadaEvent;
import inv.checkout.infrastructure.persistence.PagamentoRepository;
import inv.checkout.infrastructure.persistence.VendaRepository;
import inv.checkout.infrastructure.web.dto.VendaStatusResponse;
import inv.inventory.api.ComandoEstoqueVenda;
import inv.inventory.api.EstoqueCommandService;
import inv.inventory.api.ItemComando;
import inv.shared.config.RabbitMQConfig;
import inv.shared.event.VendaPagaEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Slf4j
@Component
@RequiredArgsConstructor
public class VendaEventHandler {

    public static final String METRICA_PAGAMENTO_EXCEDENTE = "pagamento.excedente.total";

    private final VendaRepository vendaRepository;
    private final PagamentoRepository pagamentoRepository;
    private final EstoqueCommandService estoqueCommandService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_PAGAMENTO_CONFIRMADO)
    @Retryable(
            retryFor = { OptimisticLockingFailureException.class },
            backoff = @Backoff(delay = 50, multiplier = 2.0, maxDelay = 1000, random = true)
    )
    @Transactional
    public void handlePagamentoConfirmado(String payload) {
        try {
            PagamentoConfirmadoEvent event = objectMapper.readValue(payload, PagamentoConfirmadoEvent.class);

            Venda venda = vendaRepository.findByIdWithLock(event.vendaId())
                    .orElseThrow(() -> new BusinessException("Venda não encontrada"));

            Pagamento pagamento = pagamentoRepository.findById(event.pagamentoId())
                    .orElseThrow(() -> new BusinessException("Pagamento não encontrado"));

            // Condição 0: Integridade referencial entre evento, venda e pagamento
            if (pagamento.getVenda() == null || !pagamento.getVenda().getId().equals(event.vendaId())) {
                throw new BusinessException("Pagamento não pertence à venda informada.");
            }

            // Condição 1 (ACK idempotente): pagamento já processado, excedente ou estornado
            if (pagamento.isContabilizado() || pagamento.isExcedente() || pagamento.getStatus() == StatusPagamento.ESTORNADO) {
                log.info("Pagamento {} da venda {} já processado anteriormente (contabilizado={}, excedente={}, status={}). Ignorando reentrega idempotente.",
                        pagamento.getId(), event.vendaId(), pagamento.isContabilizado(), pagamento.isExcedente(), pagamento.getStatus());
                return;
            }

            // Condição 2 (Pagamento tardio/órfão Q7): venda já finalizada (CONCLUIDA ou CANCELADA)
            if (venda.getStatus() == StatusVenda.CONCLUIDA || venda.getStatus() == StatusVenda.CANCELADA) {
                pagamento.marcarComoExcedenteParaEstorno();
                pagamentoRepository.save(pagamento);
                meterRegistry.counter(METRICA_PAGAMENTO_EXCEDENTE).increment();
                log.warn("Pagamento tardio/órfão recebido para venda {} em status {}. Pagamento {} marcado como excedente para estorno manual.",
                        venda.getId(), venda.getStatus(), pagamento.getId());
                return;
            }

            // Condição 3 (Processamento normal): venda aceita pagamento
            if (venda.getStatus().aceitaPagamento()) {
                BigDecimal trocoAnterior = venda.getValorTroco() != null ? venda.getValorTroco() : BigDecimal.ZERO;
                venda.registrarPagamentoConfirmado(pagamento);
                BigDecimal trocoAtual = venda.getValorTroco() != null ? venda.getValorTroco() : BigDecimal.ZERO;
                BigDecimal trocoDestePagamento = trocoAtual.subtract(trocoAnterior).max(BigDecimal.ZERO);
                BigDecimal valorRecebido = pagamento.getValorRecebido() != null ? pagamento.getValorRecebido() : BigDecimal.ZERO;
                BigDecimal valorLiquidoRetido = valorRecebido.subtract(trocoDestePagamento).max(BigDecimal.ZERO);

                if (venda.estaPaga()) {
                    venda.concluir();
                    ComandoEstoqueVenda comando = new ComandoEstoqueVenda(
                            venda.getId(),
                            venda.getItens().stream()
                                    .map(item -> new ItemComando(item.getProdutoId(), item.getQuantidade()))
                                    .toList());
                    estoqueCommandService.confirmarBaixa(comando);
                }

                vendaRepository.save(venda);

                eventPublisher.publishEvent(new VendaPagaEvent(
                        venda.getId(),
                        pagamento.getUuidReferencia(),
                        valorLiquidoRetido
                ));

                eventPublisher.publishEvent(new VendaAtualizadaEvent(venda.getId(), VendaStatusResponse.from(venda)));
                return;
            }

            // Condição 4: Demais estados incompatíveis com pagamento
            throw new BusinessException("Venda em status inválido para confirmação de pagamento: " + venda.getStatus());

        } catch (Exception e) {
            log.error("Erro ao processar evento. Voltará para a fila.", e);
            throw new RuntimeException(e);
        }
    }
}
