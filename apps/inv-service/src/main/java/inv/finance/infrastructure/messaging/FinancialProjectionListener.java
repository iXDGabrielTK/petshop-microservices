package inv.finance.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import inv.finance.domain.model.ProjectionRetry;
import inv.finance.domain.model.TipoLancamento;
import inv.finance.infrastructure.persistence.FinancialProjectionRepository;
import inv.finance.infrastructure.persistence.ProjectionRetryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

@Slf4j
@Component
public class FinancialProjectionListener {

    private final FinancialProjectionRepository projectionRepository;
    private final ProjectionRetryRepository retryRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate projectionTxTemplate;
    private final ObjectMapper objectMapper;

    public FinancialProjectionListener(
            FinancialProjectionRepository projectionRepository,
            ProjectionRetryRepository retryRepository,
            JdbcTemplate jdbcTemplate,
            @Qualifier("projectionTxTemplate") TransactionTemplate projectionTxTemplate,
            ObjectMapper objectMapper) {
        this.projectionRepository = projectionRepository;
        this.retryRepository = retryRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.projectionTxTemplate = projectionTxTemplate;
        this.objectMapper = objectMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("projectionExecutor")
    public void onLancamentoRegistrado(LancamentoRegistradoEvent event) {
        processarComIdempotencia(event);
    }

    public void processarComIdempotencia(LancamentoRegistradoEvent event) {
        try {
            projectionTxTemplate.executeWithoutResult(status -> {
                jdbcTemplate.update("INSERT INTO financial_projection_checkpoint (lancamento_id) VALUES (?)", event.lancamentoId());
                BigDecimal credito = event.tipo() == TipoLancamento.CREDITO ? event.valorAssinado().abs() : BigDecimal.ZERO;
                BigDecimal estorno = event.tipo() == TipoLancamento.ESTORNO ? event.valorAssinado().abs() : BigDecimal.ZERO;
                projectionRepository.upsertProjection(event.vendaId(), event.valorAssinado(), credito, estorno);
            });
            log.debug("✅ Projeção CQRS atualizada. Lançamento ID: {}", event.lancamentoId());

        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.info("🔁 Evento ignorado pelo Checkpoint CQRS (Já processado). Lançamento ID: {}", event.lancamentoId());
        } catch (Exception e) {
            log.error("🚨 Falha no Upsert CQRS. Enviando para Retry Queue. Lançamento: {}", event.lancamentoId(), e);
            salvarNaFilaDeRetry(event, e.getMessage());
        }
    }

    private void salvarNaFilaDeRetry(LancamentoRegistradoEvent event, String erro) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            retryRepository.save(new ProjectionRetry(event.lancamentoId(), payload, erro));
        } catch (DataIntegrityViolationException ignored) {
            log.info("⚠️ Lançamento ID: {} já se encontra na Fila de Retry.", event.lancamentoId());
        } catch (Exception ex) {
            log.error("💀 CRÍTICO: Falha ao serializar evento para a tabela de Retry!", ex);
        }
    }
}