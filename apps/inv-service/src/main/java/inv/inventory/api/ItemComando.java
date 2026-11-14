/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.inventory.api;

import java.math.BigDecimal;
import java.util.Objects;

public record ItemComando(long produtoId, BigDecimal quantidade) {
    public ItemComando {
        if (produtoId <= 0) throw new IllegalArgumentException("produtoId deve ser positivo");
        Objects.requireNonNull(quantidade, "quantidade");
        if (quantidade.signum() <= 0) throw new IllegalArgumentException("quantidade deve ser positiva");
    }
}
