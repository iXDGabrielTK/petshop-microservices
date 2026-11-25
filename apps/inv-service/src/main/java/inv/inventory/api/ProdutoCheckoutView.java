/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.inventory.api;

import java.math.BigDecimal;
import java.util.Objects;

public record ProdutoCheckoutView(
        Long id,
        String nome,
        BigDecimal precoVenda,
        UnidadeMedidaCheckout unidadeMedida) {

    public ProdutoCheckoutView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nome, "nome");
        Objects.requireNonNull(precoVenda, "precoVenda");
        Objects.requireNonNull(unidadeMedida, "unidadeMedida");
    }

    public ProdutoCheckoutView(Long id, String nome, BigDecimal precoVenda) {
        this(id, nome, precoVenda, UnidadeMedidaCheckout.UN);
    }
}
