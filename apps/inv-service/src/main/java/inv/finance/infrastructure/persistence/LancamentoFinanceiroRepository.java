package inv.finance.infrastructure.persistence;

import inv.finance.infrastructure.web.dto.SnapshotResult;
import inv.finance.domain.model.LancamentoFinanceiro;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LancamentoFinanceiroRepository extends JpaRepository<LancamentoFinanceiro, Long> {

    @Query(value = """
        WITH limite AS (
           SELECT COALESCE(MAX(id), 0) AS max_id FROM lancamentos_financeiros
        )
        SELECT
           limite.max_id AS ultimoLancamentoId,
           COALESCE(SUM(CASE WHEN valor > 0 THEN valor ELSE 0 END), 0) AS creditos,
           COALESCE(SUM(CASE WHEN valor < 0 THEN ABS(valor) ELSE 0 END), 0) AS debitos
        FROM lancamentos_financeiros, limite
        WHERE lancamentos_financeiros.id > :ultimoId
          AND lancamentos_financeiros.id <= limite.max_id
        GROUP BY limite.max_id
        """, nativeQuery = true)
    SnapshotResult calcularSnapshotAtomico(@Param("ultimoId") long ultimoId);

}