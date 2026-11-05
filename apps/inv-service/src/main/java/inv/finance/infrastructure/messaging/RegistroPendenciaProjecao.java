package inv.finance.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import inv.finance.infrastructure.persistence.ProjectionRetryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistência da pendência de projeção CQRS de um lançamento do ledger (projection_retry_queue).
 * <p>
 * A pendência nasce na mesma transação do INSERT em lancamentos_financeiros (propagação MANDATORY),
 * de modo que o commit do ledger garante a existência da pendência e o rollback a reverte.
 * Ela é consumida por {@link FinancialProjectionListener#projetar(LancamentoRegistradoEvent)},
 * acionado pelo caminho assíncrono pós-commit e pelo ProjectionRetryScheduler.
 */
@Component
class RegistroPendenciaProjecao {

    private final ProjectionRetryRepository retryRepository;
    private final ObjectMapper objectMapper;
    private final long atrasoSchedulerSegundos;
    private final boolean diariaHabilitada;

    RegistroPendenciaProjecao(
            ProjectionRetryRepository retryRepository,
            ObjectMapper objectMapper,
            @Value("${finance.projection.pendencia.atraso-scheduler-segundos:30}") long atrasoSchedulerSegundos,
            @Value("${finance.projection.diaria.enabled:true}") boolean diariaHabilitada) {
        this.retryRepository = retryRepository;
        this.objectMapper = objectMapper;
        this.atrasoSchedulerSegundos = atrasoSchedulerSegundos;
        this.diariaHabilitada = diariaHabilitada;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrarNaTransacaoDoLedger(LancamentoRegistradoEvent event) {
        retryRepository.registrarPendencia(event.lancamentoId(), serializar(event), atrasoSchedulerSegundos);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrarFalhaAssincrona(LancamentoRegistradoEvent event, String erro) {
        if (retryRepository.bloquearLancamento(event.lancamentoId()) != null) {
            retryRepository.registrarFalhaAssincrona(event.lancamentoId(), serializar(event), erro, diariaHabilitada);
        }
    }

    private String serializar(LancamentoRegistradoEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar pendência de projeção do lançamento " + event.lancamentoId(), e);
        }
    }
}
