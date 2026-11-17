/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.checkout.infrastructure.web.dto;

import inv.shared.domain.MetodoPagamento;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public record CriarIntencaoPagamentoRequest(
        @NotNull(message = "O UUID de referência é obrigatório")
        UUID uuidReferencia,

        @NotNull(message = "Método de pagamento é obrigatório")
        MetodoPagamento metodo,

        @NotNull(message = "Valor é obrigatório")
        @Positive(message = "Valor deve ser maior que zero")
        @Digits(integer = 8, fraction = 2, message = "Valor deve ter no máximo 8 dígitos inteiros e 2 casas decimais")
        BigDecimal valor
) {
    public CriarIntencaoPagamentoRequest {
        if (valor != null) {
            BigDecimal normalizado = valor.stripTrailingZeros();
            valor = normalizado.scale() < 0 ? normalizado.setScale(0) : normalizado;
        }
    }
}
