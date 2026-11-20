/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.checkout.infrastructure.web.dto;

import inv.shared.domain.MetodoPagamento;
import jakarta.validation.constraints.DecimalMax;
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
        @DecimalMax(value = CriarIntencaoPagamentoRequest.LIMITE, message = CriarIntencaoPagamentoRequest.ERRO_PRECISAO)
        @Digits(integer = 8, fraction = 2, message = CriarIntencaoPagamentoRequest.ERRO_PRECISAO)
        BigDecimal valor
) {
    private static final String LIMITE = "99999999.99";
    private static final BigDecimal VALOR_MAXIMO = new BigDecimal(LIMITE);
    private static final String ERRO_PRECISAO = "Valor deve ter no máximo 8 dígitos inteiros e 2 casas decimais";

    public CriarIntencaoPagamentoRequest {
        if (valor != null && valor.signum() > 0 && valor.compareTo(VALOR_MAXIMO) <= 0) {
            BigDecimal normalizado = valor.stripTrailingZeros();
            valor = normalizado.scale() < 0 ? normalizado.setScale(0) : normalizado;
        }
    }
}
