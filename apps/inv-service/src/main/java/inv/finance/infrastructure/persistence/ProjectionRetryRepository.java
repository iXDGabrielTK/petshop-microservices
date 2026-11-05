package inv.finance.infrastructure.persistence;

import inv.finance.domain.model.ProjectionRetry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface ProjectionRetryRepository extends JpaRepository<ProjectionRetry, Long> {

    /** Candidatos, não uma reserva: instâncias podem selecionar o mesmo lançamento. */
    @Query(value = """
        SELECT *
        FROM projection_retry_queue
        WHERE proxima_execucao <= now()
          AND tentativas < 5
          AND status = 'PENDENTE'
        ORDER BY proxima_execucao, id
        LIMIT 100
    """, nativeQuery = true)
    List<ProjectionRetry> buscarPendentes();

    /** Serializa projeção/quitação e criação de retry legado na transação do chamador. */
    @Query(value = "SELECT id FROM lancamentos_financeiros WHERE id = :lancamentoId FOR UPDATE", nativeQuery = true)
    Long bloquearLancamento(@Param("lancamentoId") long lancamentoId);

    /**
     * Registra a pendência de projeção de um lançamento recém-inserido no ledger.
     * Deve executar na mesma transação do INSERT do lançamento: o rollback do ledger reverte a pendência.
     * A pendência só fica elegível ao scheduler após {@code atrasoSegundos}, dando precedência ao caminho assíncrono.
     */
    @Modifying
    @Query(value = """
        INSERT INTO projection_retry_queue (lancamento_id, payload, tentativas, status, proxima_execucao)
        VALUES (:lancamentoId, CAST(:payload AS jsonb), 0, 'PENDENTE', now() + (:atrasoSegundos * INTERVAL '1 second'))
        ON CONFLICT (lancamento_id) DO NOTHING
        """, nativeQuery = true)
    int registrarPendencia(@Param("lancamentoId") long lancamentoId,
                           @Param("payload") String payload,
                           @Param("atrasoSegundos") long atrasoSegundos);

    /**
     * Registra a falha do caminho assíncrono e torna a pendência elegível imediatamente ao scheduler.
     * Cria pendência legada somente se falta alguma projeção aplicável. O chamador deve primeiro
     * bloquear o lançamento para impedir recriação após quitação concorrente. Backoff já registrado é preservado.
     */
    @Modifying
    @Query(value = """
        INSERT INTO projection_retry_queue (lancamento_id, payload, erro, tentativas, status, proxima_execucao)
        SELECT :lancamentoId, CAST(:payload AS jsonb), :erro, 0, 'PENDENTE', now()
        WHERE NOT EXISTS (SELECT 1 FROM financial_projection_checkpoint_v2 WHERE lancamento_id = :lancamentoId)
           OR (:diariaHabilitada AND NOT EXISTS
               (SELECT 1 FROM financial_projection_diaria_checkpoint WHERE lancamento_id = :lancamentoId))
        ON CONFLICT (lancamento_id) DO UPDATE SET
            erro = EXCLUDED.erro,
            proxima_execucao = LEAST(projection_retry_queue.proxima_execucao, EXCLUDED.proxima_execucao)
        WHERE projection_retry_queue.tentativas = 0 AND projection_retry_queue.erro IS NULL
          AND projection_retry_queue.status = 'PENDENTE'
        """, nativeQuery = true)
    int registrarFalhaAssincrona(@Param("lancamentoId") long lancamentoId,
                                 @Param("payload") String payload,
                                 @Param("erro") String erro,
                                 @Param("diariaHabilitada") boolean diariaHabilitada);

    /** Compare-and-set: uma seleção antiga não recria linha quitada nem sobrescreve outro backoff. */
    @Transactional
    @Modifying
    @Query(value = """
        UPDATE projection_retry_queue
           SET erro = :erro, tentativas = :tentativasAnteriores + 1,
               status = :status, proxima_execucao = :proximaExecucao
         WHERE id = :id AND status = 'PENDENTE' AND tentativas = :tentativasAnteriores
           AND proxima_execucao = :execucaoAnterior
        """, nativeQuery = true)
    int registrarFalhaSeAtual(@Param("id") long id,
                             @Param("tentativasAnteriores") int tentativasAnteriores,
                             @Param("execucaoAnterior") LocalDateTime execucaoAnterior,
                             @Param("erro") String erro,
                             @Param("status") String status,
                             @Param("proximaExecucao") LocalDateTime proximaExecucao);

    /** Quita a pendência; deve executar na mesma transação que confirma todas as projeções aplicáveis. */
    @Modifying
    @Query(value = "DELETE FROM projection_retry_queue WHERE lancamento_id = :lancamentoId", nativeQuery = true)
    int quitarPendencia(@Param("lancamentoId") long lancamentoId);
}
