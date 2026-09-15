package inv.checkout.usecase;

import common.exception.BusinessException;
import inv.checkout.infrastructure.messaging.event.PagamentoConfirmadoEvent;
import inv.checkout.domain.model.MetodoPagamento;
import inv.checkout.domain.model.Pagamento;
import inv.checkout.domain.model.StatusPagamento;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.persistence.PagamentoRepository;
import inv.checkout.infrastructure.persistence.VendaRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Service
public class PagamentoService {

    private final PagamentoRepository pagamentoRepository;
    private final VendaRepository vendaRepository;
    private final ApplicationEventPublisher eventPublisher;

    public PagamentoService(PagamentoRepository pagamentoRepository, VendaRepository vendaRepository, ApplicationEventPublisher eventPublisher) {
        this.pagamentoRepository = pagamentoRepository;
        this.vendaRepository = vendaRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Long criarIntencaoPagamento(Long vendaId,
                                       MetodoPagamento metodo,
                                       BigDecimal valor,
                                       UUID uuidReferencia) {

        Optional<Pagamento> pagamentoExistente = pagamentoRepository.findByUuidReferencia(uuidReferencia);
        if (pagamentoExistente.isPresent()) {
            return pagamentoExistente.get().getId();
        }

        Venda venda = vendaRepository.findById(vendaId)
                .orElseThrow(() -> new BusinessException("Venda não encontrada"));

        Pagamento pagamento = new Pagamento(venda, metodo, valor, uuidReferencia);

        if (metodo.isExigeProcessamento()) {
            pagamento.processar();
        }

        return pagamentoRepository.save(pagamento).getId();
    }

    @Transactional(readOnly = true)
    public StatusPagamento buscarStatusPagamento(Long pagamentoId) {
        return pagamentoRepository.findById(pagamentoId)
                .map(Pagamento::getStatus)
                .orElseThrow(() -> new BusinessException("Pagamento não encontrado"));
    }

    @Transactional
    public void confirmarPagamentoWebhook(Long pagamentoId, BigDecimal valor, String nsu) {
        Pagamento pagamento = pagamentoRepository.findById(pagamentoId)
                .orElseThrow(() -> new BusinessException("Pagamento não encontrado para o ID: " + pagamentoId));

        pagamento.confirmar(valor, nsu);
        pagamentoRepository.save(pagamento);

        eventPublisher.publishEvent(new PagamentoConfirmadoEvent(pagamento.getVenda().getId(), pagamento.getId()));
    }

    @Transactional
    public void falharPagamentoWebhook(Long pagamentoId) {
        Pagamento pagamento = pagamentoRepository.findById(pagamentoId)
                .orElseThrow(() -> new BusinessException("Pagamento não encontrado para o ID: " + pagamentoId));

        pagamento.falhar();
        pagamentoRepository.save(pagamento);
    }
}