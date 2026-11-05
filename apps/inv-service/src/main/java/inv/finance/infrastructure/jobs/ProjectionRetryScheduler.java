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
import java.time.LocalDateTime;

/**
 * Consumidor automático das pendências de projeção (projection_retry_queue), sejam elas registradas na
 * transação do ledger ou após falha do caminho assíncrono. A quitação ocorre dentro de
 * {@link FinancialProjectionListener#projetar(LancamentoRegistradoEvent)}, na mesma transação das projeções;
 * em caso de falha a pendência é mantida com backoff.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectionRetryScheduler {

    private final ProjectionRetryRepository retryRepository;
    private final FinancialProjectionListener projectionListener;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${finance.projection.retry.fixed-delay-ms:60000}")
    public void reprocessarFalhasDeProjecao() {
        List<ProjectionRetry> pendentes = retryRepository.buscarPendentes();

        if (pendentes.isEmpty()) return;

        log.info("🛠️ Auto-cura CQRS ativada: Processando {} eventos pendentes na Fila de Retry.", pendentes.size());

        for (ProjectionRetry retry : pendentes) {
            try {
                LancamentoRegistradoEvent event = objectMapper.readValue(retry.getPayload(), LancamentoRegistradoEvent.class);

                projectionListener.projetar(event);

                log.info("✅ Retry CQRS bem sucedido para Lançamento ID: {}", event.lancamentoId());

            } catch (Exception e) {
                registrarFalha(retry, e);
            }
        }
    }

    private void registrarFalha(ProjectionRetry retry, Exception causa) {
        try {
            int tentativasAnteriores = retry.getTentativas();
            LocalDateTime execucaoAnterior = retry.getProximaExecucao();
            retry.registrarFalha(causa.getMessage() != null ? causa.getMessage() : causa.getClass().getName());
            int atualizados = retryRepository.registrarFalhaSeAtual(retry.getId(), tentativasAnteriores,
                    execucaoAnterior, retry.getErro(), retry.getStatus(), retry.getProximaExecucao());
            if (atualizados == 1) {
                log.warn("⚠️ Retry CQRS falhou novamente para Lançamento ID: {}. Tentativa {}",
                        retry.getLancamentoId(), retry.getTentativas(), causa);
            }
        } catch (Exception ex) {
            log.warn("⚠️ Não foi possível registrar a falha da pendência do Lançamento ID: {} (possivelmente quitada concorrentemente).",
                    retry.getLancamentoId(), ex);
        }
    }
}
