/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.inventory.infrastructure.persistence;

import inv.inventory.domain.model.MovimentacaoEstoque;
import inv.inventory.domain.model.TipoMovimentacao;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MovimentacaoRepository extends JpaRepository<MovimentacaoEstoque, Long> {

    @Query("""
        SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END
          FROM MovimentacaoEstoque m
         WHERE m.vendaId = :vendaId
           AND m.produto.id = :produtoId
           AND m.tipo = :tipo
        """)
    boolean existsByVendaIdAndProdutoIdAndTipo(
            @Param("vendaId") Long vendaId,
            @Param("produtoId") Long produtoId,
            @Param("tipo") TipoMovimentacao tipo
    );
}
