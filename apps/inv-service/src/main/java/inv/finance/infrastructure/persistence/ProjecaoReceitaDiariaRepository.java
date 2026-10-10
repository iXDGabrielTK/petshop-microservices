package inv.finance.infrastructure.persistence;

import inv.finance.domain.model.LancamentoFinanceiro;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

@org.springframework.stereotype.Repository
@Transactional
public interface ProjecaoReceitaDiariaRepository
        extends Repository<LancamentoFinanceiro, Long> {

    /**
     * Aplica um lançamento à projeção diária exatamente uma vez.
     * A data vem da própria linha do ledger (fonte da verdade), o que é seguro em retries tardios.
     * Se o checkpoint já existir, o CTE não retorna linhas e o INSERT externo vira no-op.
     */
    @Modifying
    @Query(value = """
        WITH marcado AS (
            INSERT INTO financial_projection_diaria_checkpoint (lancamento_id)
            VALUES (:lancamentoId)
            ON CONFLICT DO NOTHING
            RETURNING lancamento_id
        )
        INSERT INTO financial_projection_diaria AS d
               (data_referencia, total_creditos, total_debitos, qtd_lancamentos)
        SELECT lf.data_hora::date,
               GREATEST(lf.valor, 0),
               GREATEST(-lf.valor, 0),
               1
          FROM marcado m
          JOIN lancamentos_financeiros lf ON lf.id = m.lancamento_id
        ON CONFLICT (data_referencia) DO UPDATE SET
               total_creditos     = d.total_creditos  + EXCLUDED.total_creditos,
               total_debitos      = d.total_debitos   + EXCLUDED.total_debitos,
               qtd_lancamentos    = d.qtd_lancamentos + EXCLUDED.qtd_lancamentos,
               ultima_atualizacao = CURRENT_TIMESTAMP
        """, nativeQuery = true)
    int aplicar(@Param("lancamentoId") long lancamentoId);

    /** Backfill/catch-up por faixa de IDs: idempotente e seguro em concorrência com o listener. */
    @Modifying
    @Query(value = """
        WITH marcado AS (
            INSERT INTO financial_projection_diaria_checkpoint (lancamento_id)
            SELECT id FROM lancamentos_financeiros WHERE id > :de AND id <= :ate
            ON CONFLICT DO NOTHING
            RETURNING lancamento_id
        )
        INSERT INTO financial_projection_diaria AS d
               (data_referencia, total_creditos, total_debitos, qtd_lancamentos)
        SELECT lf.data_hora::date, SUM(GREATEST(lf.valor,0)), SUM(GREATEST(-lf.valor,0)), COUNT(*)
          FROM marcado m JOIN lancamentos_financeiros lf ON lf.id = m.lancamento_id
         GROUP BY lf.data_hora::date
        ON CONFLICT (data_referencia) DO UPDATE SET
               total_creditos     = d.total_creditos  + EXCLUDED.total_creditos,
               total_debitos      = d.total_debitos   + EXCLUDED.total_debitos,
               qtd_lancamentos    = d.qtd_lancamentos + EXCLUDED.qtd_lancamentos,
               ultima_atualizacao = CURRENT_TIMESTAMP
        """, nativeQuery = true)
    int aplicarFaixa(@Param("de") long deExclusivo, @Param("ate") long ateInclusivo);
}
