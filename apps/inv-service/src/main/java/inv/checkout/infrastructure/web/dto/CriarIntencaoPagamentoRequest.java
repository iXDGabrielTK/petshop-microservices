/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.checkout.infrastructure.web.dto;

import inv.shared.domain.MetodoPagamento;
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
        BigDecimal valor
) {}
