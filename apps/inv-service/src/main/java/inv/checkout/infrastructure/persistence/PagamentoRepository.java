/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.checkout.infrastructure.persistence;

import inv.checkout.domain.model.Pagamento;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;


@Repository
public interface PagamentoRepository extends JpaRepository<Pagamento, Long> {
    Optional<Pagamento> findByUuidReferencia(UUID uuidReferencia);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Pagamento p WHERE p.id = :id")
    Optional<Pagamento> findByIdWithLock(@Param("id") Long id);

    @Query(value = """
            INSERT INTO pagamentos (
                uuid_referencia, venda_id, metodo, status,
                valor_esperado, valor_recebido, excedente, contabilizado
            )
            VALUES (
                :uuidReferencia, :vendaId, :metodo, :status,
                :valorEsperado, :valorRecebido, :excedente, :contabilizado
            )
            ON CONFLICT (uuid_referencia) DO NOTHING
            RETURNING id
            """, nativeQuery = true)
    Optional<Long> inserirIntencaoIdempotente(
            @Param("uuidReferencia") UUID uuidReferencia,
            @Param("vendaId") Long vendaId,
            @Param("metodo") String metodo,
            @Param("status") String status,
            @Param("valorEsperado") BigDecimal valorEsperado,
            @Param("valorRecebido") BigDecimal valorRecebido,
            @Param("excedente") boolean excedente,
            @Param("contabilizado") boolean contabilizado
    );
}
