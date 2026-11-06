/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.inventory.infrastructure.web.dto;

import inv.inventory.domain.model.UnidadeMedida;

import java.math.BigDecimal;

public record ProdutoResponse(
        Long id,
        String codigoBarras,
        String nome,
        UnidadeMedida unidadeMedida,
        BigDecimal quantidadeEstoque,
        BigDecimal precoVenda
) {}
