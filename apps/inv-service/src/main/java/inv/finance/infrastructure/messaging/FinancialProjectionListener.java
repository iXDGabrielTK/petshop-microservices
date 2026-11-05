package inv.finance.infrastructure.messaging;

import inv.finance.domain.model.TipoLancamento;
import inv.finance.infrastructure.persistence.FinancialProjectionRepository;
import inv.finance.infrastructure.persistence.ProjectionRetryRepository;
import inv.finance.infrastructure.persistence.ProjecaoReceitaDiariaRepository;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Projeções CQRS do ledger financeiro (financial_projection_venda e financial_projection_diaria).
 * <p>
 * A rotina idempotente {@link #projetar(LancamentoRegistradoEvent)} é o único ponto de aplicação das projeções
 * e é compartilhada pelo caminho assíncrono pós-commit e pelo ProjectionRetryScheduler. Cada projeção é protegida
 * pelo seu próprio checkpoint, e a pendência persistida na transação do ledger só é quitada na mesma transação que
 * confirma todas as projeções aplicáveis.
 */
@Slf4j
@Component
public class FinancialProjectionListener {

    /** Tentativas para absorver conflitos de serialização/deadlock entre execuções concorrentes do mesmo lançamento. */
    private static final int MAX_TENTATIVAS_CONFLITO = 3;

    private static final String SQL_CHECKPOINT_VENDA = """
            INSERT INTO financial_projection_checkpoint_v2 (lancamento_id) VALUES (?)
            ON CONFLICT (lancamento_id) DO NOTHING
            """;

    private final FinancialProjectionRepository projectionRepository;
    private final ProjectionRetryRepository retryRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate projectionTxTemplate;
    private final RegistroPendenciaProjecao registroPendencia;
    private final ProjecaoReceitaDiariaRepository projecaoDiariaRepository;
    private final boolean projecaoDiariaHabilitada;

    FinancialProjectionListener(
            FinancialProjectionRepository projectionRepository,
            ProjectionRetryRepository retryRepository,
            JdbcTemplate jdbcTemplate,
            @Qualifier("projectionTxTemplate") TransactionTemplate projectionTxTemplate,
            RegistroPendenciaProjecao registroPendencia,
            ProjecaoReceitaDiariaRepository projecaoDiariaRepository,
            @Value("${finance.projection.diaria.enabled:true}") boolean projecaoDiariaHabilitada) {
        this.projectionRepository = projectionRepository;
        this.retryRepository = retryRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.projectionTxTemplate = projectionTxTemplate;
        this.registroPendencia = registroPendencia;
        this.projecaoDiariaRepository = projecaoDiariaRepository;
        this.projecaoDiariaHabilitada = projecaoDiariaHabilitada;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("projectionExecutor")
    public void onLancamentoRegistrado(LancamentoRegistradoEvent event) {
        processarComIdempotencia(event);
    }

    /**
     * Caminho assíncrono: aplica as projeções e, em caso de falha, registra o erro na pendência
     * tornando-a elegível imediatamente ao scheduler.
     */
    public void processarComIdempotencia(LancamentoRegistradoEvent event) {
        try {
            projetar(event);
        } catch (Exception e) {
            log.error("🚨 Falha no Upsert CQRS. Enviando para Retry Queue. Lançamento: {}", event.lancamentoId(), e);
            salvarNaFilaDeRetry(event, e);
        }
    }

    /**
     * Rotina idempotente única de projeção. Em uma única transação: aplica a projeção por venda se o seu checkpoint
     * for inédito, aplica a projeção diária (checkpoint próprio) quando habilitada e quita a pendência do lançamento.
     * Conflitos transitórios com execuções concorrentes são reexecutados; demais falhas são propagadas e a pendência
     * permanece intacta pelo rollback.
     */
    public void projetar(LancamentoRegistradoEvent event) {
        for (int tentativa = 1; ; tentativa++) {
            try {
                projectionTxTemplate.executeWithoutResult(status -> aplicarProjecoesEQuitarPendencia(event));
                log.debug("✅ Projeção CQRS atualizada. Lançamento ID: {}", event.lancamentoId());
                return;
            } catch (RuntimeException e) {
                if (tentativa >= MAX_TENTATIVAS_CONFLITO || !isConflitoTransitorio(e)) {
                    throw e;
                }
                log.info("🔁 Conflito concorrente na projeção do lançamento {} (tentativa {}). Reexecutando.",
                        event.lancamentoId(), tentativa);
            }
        }
    }

    private void aplicarProjecoesEQuitarPendencia(LancamentoRegistradoEvent event) {
        if (retryRepository.bloquearLancamento(event.lancamentoId()) == null) {
            throw new IllegalStateException("Lançamento inexistente: " + event.lancamentoId());
        }
        int checkpointVendaInedito = jdbcTemplate.update(SQL_CHECKPOINT_VENDA, event.lancamentoId());
        if (checkpointVendaInedito == 1) {
            BigDecimal credito = event.tipo() == TipoLancamento.CREDITO ? event.valorAssinado().abs() : BigDecimal.ZERO;
            BigDecimal estorno = event.tipo() == TipoLancamento.ESTORNO ? event.valorAssinado().abs() : BigDecimal.ZERO;
            projectionRepository.upsertProjection(event.vendaId(), event.valorAssinado(), credito, estorno);
        } else {
            log.info("🔁 Projeção por venda já aplicada pelo Checkpoint CQRS. Lançamento ID: {}", event.lancamentoId());
        }
        if (projecaoDiariaHabilitada) {
            projecaoDiariaRepository.aplicar(event.lancamentoId());
        }
        retryRepository.quitarPendencia(event.lancamentoId());
    }

    private void salvarNaFilaDeRetry(LancamentoRegistradoEvent event, Exception causa) {
        String erro = causa.getMessage() != null ? causa.getMessage() : causa.getClass().getName();
        try {
            registroPendencia.registrarFalhaAssincrona(event, erro);
        } catch (Exception ex) {
            log.error("💀 CRÍTICO: Falha ao registrar a falha na tabela de Retry! Lançamento ID: {}", event.lancamentoId(), ex);
        }
    }

    private static boolean isConflitoTransitorio(Throwable e) {
        for (Throwable atual = e; atual != null; atual = atual.getCause()) {
            if (atual instanceof ConcurrencyFailureException) {
                return true;
            }
            if (atual instanceof SQLException sql && ("40001".equals(sql.getSQLState()) || "40P01".equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }
}
