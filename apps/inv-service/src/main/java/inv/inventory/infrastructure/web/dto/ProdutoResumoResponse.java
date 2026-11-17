/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.inventory.infrastructure.web.dto;

import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.UnidadeMedida;
import java.math.BigDecimal;

public record ProdutoResumoResponse(
        Long id, String codigoBarras, String nome, UnidadeMedida unidadeMedida,
        BigDecimal estoqueDisponivel, BigDecimal precoVenda
) {
    public static ProdutoResumoResponse from(Produto produto) {
        return new ProdutoResumoResponse(produto.getId(), produto.getCodigoBarras(),
                produto.getNome(), produto.getUnidadeMedida(),
                produto.getEstoqueDisponivel(), produto.getPrecoVenda());
    }
}
