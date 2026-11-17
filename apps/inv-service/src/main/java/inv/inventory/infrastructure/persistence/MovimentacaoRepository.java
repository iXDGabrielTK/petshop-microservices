/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.inventory.infrastructure.persistence;

import inv.inventory.domain.model.MovimentacaoEstoque;
import inv.inventory.infrastructure.web.dto.MovimentacaoResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import inv.inventory.domain.model.TipoMovimentacao;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MovimentacaoRepository extends JpaRepository<MovimentacaoEstoque, Long> {

    @Query(value = """
        SELECT new inv.inventory.infrastructure.web.dto.MovimentacaoResponse(
            m.id, p.id, p.nome, p.codigoBarras, m.vendaId, m.tipo, m.quantidade, m.dataHora, m.observacao
        )
        FROM MovimentacaoEstoque m
        JOIN m.produto p
        """, countQuery = "SELECT COUNT(m) FROM MovimentacaoEstoque m")
    Page<MovimentacaoResponse> listarResumo(Pageable pageable);

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
