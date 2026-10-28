package inv.finance.infrastructure.persistence;

import inv.finance.infrastructure.web.dto.DivergenciaResult;
import inv.finance.domain.model.ReconciliacaoCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ReconciliacaoRepository extends JpaRepository<ReconciliacaoCheckpoint, Long> {

    Optional<ReconciliacaoCheckpoint> findTopByOrderByIdDesc();

    @Query(value = """
        WITH vendas_movimentadas AS (
            SELECT DISTINCT venda_id
            FROM lancamentos_financeiros
            WHERE id > :ultimoReconhecido AND id <= :novoMaxId
        )
        SELECT
            fpv.venda_id AS vendaId,
            fpv.saldo AS saldoProjetado,
            SUM(lf.valor) AS saldoReal,
            ABS(fpv.saldo - SUM(lf.valor)) AS divergencia
        FROM vendas_movimentadas vm
        JOIN lancamentos_financeiros lf ON lf.venda_id = vm.venda_id
        JOIN financial_projection_venda fpv ON fpv.venda_id = vm.venda_id
        GROUP BY fpv.venda_id, fpv.saldo
        HAVING fpv.saldo <> SUM(lf.valor)
        """, nativeQuery = true)
    List<DivergenciaResult> auditarDivergencias(@Param("ultimoReconhecido") long ultimoReconhecido,
                                                @Param("novoMaxId") long novoMaxId);
}