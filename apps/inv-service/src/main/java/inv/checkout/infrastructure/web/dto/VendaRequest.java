/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.checkout.infrastructure.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record VendaRequest(
        @NotNull(message = "O carrinho não pode estar vazio")
        @Size(min = 1, max = 100, message = "O carrinho deve conter entre 1 e 100 itens")
        @Valid
        List<@NotNull ItemVendaRequest> itens
) {}
