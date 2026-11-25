/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.checkout.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record ConfirmarPagamentoWebhookRequest(
    @NotNull(message = "valor_recebido é obrigatório")
    @Positive(message = "valor_recebido deve ser positivo")
    @Digits(integer = 8, fraction = 2)
    BigDecimal valor_recebido,
    @NotBlank(message = "nsu é obrigatório")
    @Size(max = 100)
    String nsu
) {}
