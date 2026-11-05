package inv.finance.infrastructure.messaging;

import inv.finance.domain.model.TipoLancamento;
import inv.finance.infrastructure.persistence.LancamentoFinanceiroRepository;
import inv.shared.event.VendaPagaEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class VendaPagaEventListener {

    private final LancamentoFinanceiroRepository lancamentoRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final RegistroPendenciaProjecao registroPendencia;

    /**
     * Participa da transação do publicador (REQUIRED): o lançamento e a sua pendência de projeção
     * são confirmados ou revertidos atomicamente junto com a venda.
     */
    @EventListener
    @Transactional
    public void onVendaPaga(VendaPagaEvent event) {
        if (event == null || event.vendaId() == null || event.uuidReferencia() == null) {
            log.warn("VendaPagaEvent inválido recebido (event={}). Ignorando processamento idempotente.", event);
            return;
        }

        if (lancamentoRepository.existsByReferencia(event.uuidReferencia())) {
            log.info("Lançamento com referência {} já existe para a venda {}. Ignorando processamento idempotente.",
                    event.uuidReferencia(), event.vendaId());
            return;
        }

        BigDecimal valor = event.valorRecebido() != null ? event.valorRecebido().abs() : BigDecimal.ZERO;
        Optional<Long> idGerado = lancamentoRepository.inserirIdempotente(
                event.vendaId(),
                event.uuidReferencia(),
                TipoLancamento.CREDITO.name(),
                valor
        );

        if (idGerado.isPresent()) {
            LancamentoRegistradoEvent lancamentoRegistrado = new LancamentoRegistradoEvent(
                    idGerado.get(),
                    event.vendaId(),
                    valor,
                    TipoLancamento.CREDITO
            );
            registroPendencia.registrarNaTransacaoDoLedger(lancamentoRegistrado);
            eventPublisher.publishEvent(lancamentoRegistrado);
            log.info("Lançamento financeiro {} gerado via evento interno para venda {}", idGerado.get(), event.vendaId());
        } else {
            log.info("Lançamento com referência {} já inserido concorrentemente para venda {}. Evento não emitido.",
                    event.uuidReferencia(), event.vendaId());
        }
    }
}
