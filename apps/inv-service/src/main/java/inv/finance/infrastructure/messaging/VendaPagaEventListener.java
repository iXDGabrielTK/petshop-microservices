package inv.finance.infrastructure.messaging;

import inv.shared.event.VendaPagaEvent;
import inv.finance.domain.model.LancamentoFinanceiro;
import inv.finance.domain.model.TipoLancamento;
import inv.finance.infrastructure.persistence.LancamentoFinanceiroRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class VendaPagaEventListener {

    private final LancamentoFinanceiroRepository lancamentoRepository;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    public void onVendaPaga(VendaPagaEvent event) {
        LancamentoFinanceiro lancamento = new LancamentoFinanceiro(
                event.vendaId(),
                event.uuidReferencia(),
                TipoLancamento.CREDITO,
                event.valorRecebido()
        );
        lancamentoRepository.save(lancamento);

        eventPublisher.publishEvent(new LancamentoRegistradoEvent(
                event.vendaId(),
                lancamento.getVendaId(),
                lancamento.getValor(),
                lancamento.getTipo()
        ));
        
        log.info("Lançamento financeiro gerado via evento interno para venda {}", event.vendaId());
    }
}
