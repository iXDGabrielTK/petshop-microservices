/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.inventory.api;

public record ResumoEstoque(long produtosAbaixoDoMinimo) {
    public ResumoEstoque {
        if (produtosAbaixoDoMinimo < 0) {
            throw new IllegalArgumentException("produtosAbaixoDoMinimo < 0");
        }
    }
}
