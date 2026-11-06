/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.inventory.infrastructure.web.dto;

import inv.inventory.domain.model.UnidadeMedida;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record ProdutoRequest(
        String codigoBarras,

        @NotBlank(message = "O nome do produto é obrigatório")
        @Size(max = 255)
        @Pattern(
                regexp = "^[^<>{}]*$",
                message = "HTML não é permitido neste campo"
        )
        String nome,

        @NotNull(message = "A unidade de medida é obrigatória (UN ou KG)")
        UnidadeMedida unidadeMedida,

        @NotNull
        @PositiveOrZero(message = "O estoque inicial não pode ser negativo")
        BigDecimal quantidadeEstoque,

        @NotNull
        @Positive(message = "O preço de venda deve ser maior que zero")
        BigDecimal precoVenda
) {}
