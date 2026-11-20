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
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

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
        SELECT new inv.inventory.infrastructure.persistence.SaidaPorProduto(m.produto.id, SUM(m.quantidade))
          FROM MovimentacaoEstoque m
         WHERE m.vendaId = :vendaId
           AND m.tipo = inv.inventory.domain.model.TipoMovimentacao.SAIDA
         GROUP BY m.produto.id
        """)
    List<SaidaPorProduto> buscarSaidasPorVenda(@Param("vendaId") Long vendaId);

    @Query("""
        SELECT m.quantidade
          FROM MovimentacaoEstoque m
         WHERE m.vendaId = :vendaId
           AND m.produto.id = :produtoId
           AND m.tipo = inv.inventory.domain.model.TipoMovimentacao.DEVOLUCAO
        """)
    Optional<BigDecimal> buscarQuantidadeDevolucao(
            @Param("vendaId") Long vendaId,
            @Param("produtoId") Long produtoId
    );
}
