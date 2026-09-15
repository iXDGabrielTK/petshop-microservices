package inv.finance.infrastructure.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import inv.finance.domain.model.ProjectionRetry;
import inv.finance.infrastructure.messaging.FinancialProjectionListener;
import inv.finance.infrastructure.messaging.LancamentoRegistradoEvent;
import inv.finance.infrastructure.persistence.ProjectionRetryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectionRetryScheduler {

    private final ProjectionRetryRepository retryRepository;
    private final FinancialProjectionListener projectionListener;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 60000)
    public void reprocessarFalhasDeProjecao() {
        List<ProjectionRetry> pendentes = retryRepository.buscarPendentesComLock();

        if (pendentes.isEmpty()) return;

        log.info("🛠️ Auto-cura CQRS ativada: Processando {} eventos pendentes na Fila de Retry.", pendentes.size());

        for (ProjectionRetry retry : pendentes) {
            try {
                LancamentoRegistradoEvent event = objectMapper.readValue(retry.getPayload(), LancamentoRegistradoEvent.class);

                projectionListener.processarComIdempotencia(event);

                retryRepository.delete(retry);
                log.info("✅ Retry CQRS bem sucedido para Lançamento ID: {}", event.lancamentoId());

            } catch (Exception e) {
                retry.registrarFalha(e.getMessage());
                retryRepository.save(retry);
                log.warn("⚠️ Retry CQRS falhou novamente para Lançamento ID: {}. Tentativa {}",
                        retry.getLancamentoId(), retry.getTentativas());
            }
        }
    }
}