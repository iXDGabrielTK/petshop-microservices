package inv.finance.infrastructure.persistence;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import inv.finance.domain.model.LancamentoFinanceiro;

import java.math.BigDecimal;

@Repository
public interface FinancialProjectionRepository extends org.springframework.data.repository.Repository<LancamentoFinanceiro, Long> {

    @Modifying
    @Query(value = """
        INSERT INTO financial_projection_venda (venda_id, saldo, total_creditos, total_estornos)
        VALUES (:vendaId, :valor, :credito, :estorno)
        ON CONFLICT (venda_id) DO UPDATE SET
            saldo = financial_projection_venda.saldo + EXCLUDED.saldo,
            total_creditos = financial_projection_venda.total_creditos + EXCLUDED.total_creditos,
            total_estornos = financial_projection_venda.total_estornos + EXCLUDED.total_estornos,
            ultima_atualizacao = CURRENT_TIMESTAMP
        """, nativeQuery = true)
    void upsertProjection(
            @Param("vendaId") Long vendaId,
            @Param("valor") BigDecimal valorAssinado,
            @Param("credito") BigDecimal credito,
            @Param("estorno") BigDecimal estorno
    );
}