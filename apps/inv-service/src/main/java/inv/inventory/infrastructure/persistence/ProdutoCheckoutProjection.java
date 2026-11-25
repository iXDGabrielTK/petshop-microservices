/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.inventory.infrastructure.persistence;

import inv.inventory.domain.model.UnidadeMedida;

import java.math.BigDecimal;

/** Projeção interna ao Inventory: nunca sai do contexto. */
public record ProdutoCheckoutProjection(
        Long id,
        String nome,
        BigDecimal precoVenda,
        UnidadeMedida unidadeMedida) {}
