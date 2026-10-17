package inv.finance.infrastructure.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Job de reparo da projeção CQRS de vendas (DEBT-ARCH-001 / F1).
 * <p>
 * Executado sob LOCK TABLE em modo SHARE ROW EXCLUSIVE MODE para impedir
 * escritas concorrentes na projeção durante o reparo, mantendo leituras ativas.
 * Em um único statement (mesmo snapshot MVCC), recalcula financial_projection_venda
 * a partir do ledger (lancamentos_financeiros) e popula financial_projection_checkpoint_v2
 * com os IDs exatamente visíveis nesse snapshot.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReparoProjecaoVendaJob {

    private final JdbcTemplate jdbcTemplate;

    private static final String LOCK_SQL =
            "LOCK TABLE financial_projection_venda IN SHARE ROW EXCLUSIVE MODE";

    private static final String REPARO_SQL = """
            WITH snapshot_lancamentos AS (
                SELECT id, venda_id, tipo, valor
                FROM lancamentos_financeiros
            ),
            insere_checkpoint AS (
                INSERT INTO financial_projection_checkpoint_v2 (lancamento_id)
                SELECT id FROM snapshot_lancamentos
                ON CONFLICT (lancamento_id) DO NOTHING
                RETURNING lancamento_id
            ),
            agregado AS (
                SELECT
                    venda_id,
                    SUM(valor) AS saldo,
                    SUM(CASE WHEN tipo = 'CREDITO' THEN ABS(valor) ELSE 0 END) AS total_creditos,
                    SUM(CASE WHEN tipo = 'ESTORNO' THEN ABS(valor) ELSE 0 END) AS total_estornos
                FROM snapshot_lancamentos
                GROUP BY venda_id
            )
            INSERT INTO financial_projection_venda (venda_id, saldo, total_creditos, total_estornos, ultima_atualizacao)
            SELECT venda_id, saldo, total_creditos, total_estornos, CURRENT_TIMESTAMP
            FROM agregado
            ON CONFLICT (venda_id) DO UPDATE SET
                saldo = EXCLUDED.saldo,
                total_creditos = EXCLUDED.total_creditos,
                total_estornos = EXCLUDED.total_estornos,
                ultima_atualizacao = CURRENT_TIMESTAMP
            """;

    @Transactional
    public int executarReparo() {
        log.info("Iniciando reparo atômico de financial_projection_venda e financial_projection_checkpoint_v2...");
        jdbcTemplate.execute(LOCK_SQL);
        int vendasAtualizadas = jdbcTemplate.update(REPARO_SQL);
        log.info("Reparo concluído com sucesso. Vendas atualizadas na projeção: {}", vendasAtualizadas);
        return vendasAtualizadas;
    }
}
