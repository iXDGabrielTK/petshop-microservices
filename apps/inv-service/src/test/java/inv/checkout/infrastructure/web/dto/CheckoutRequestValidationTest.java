/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.checkout.infrastructure.web.dto;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutRequestValidationTest {

    @Test
    void limitaCarrinhoEQuantidadePorItem() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var item = new ItemVendaRequest(1L, new BigDecimal("9999999.999"));
            assertThat(validator.validate(new VendaRequest(Collections.nCopies(100, item)))).isEmpty();
            for (var itens : List.of(Collections.nCopies(101, item), List.<ItemVendaRequest>of())) {
                assertThat(validator.validate(new VendaRequest(itens)))
                        .extracting(v -> v.getPropertyPath().toString()).containsExactly("itens");
            }
            for (String valor : List.of("10000000", "1.0001")) {
                var invalido = new ItemVendaRequest(1L, new BigDecimal(valor));
                assertThat(validator.validate(new VendaRequest(List.of(item, invalido))))
                        .extracting(v -> v.getPropertyPath().toString()).containsExactly("itens[1].quantidade");
            }
        }
    }

    @Test
    void validaValorENsuDoWebhook() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var limite = new ConfirmarPagamentoWebhookRequest(new BigDecimal("99999999.99"), "x".repeat(100));
            assertThat(validator.validate(limite)).isEmpty();
            assertThat(validator.validate(new ConfirmarPagamentoWebhookRequest(BigDecimal.ONE, "x".repeat(101))))
                    .extracting(v -> v.getPropertyPath().toString()).containsExactly("nsu");
            for (String valor : List.of("100000000", "1.001")) {
                assertThat(validator.validate(new ConfirmarPagamentoWebhookRequest(new BigDecimal(valor), "nsu")))
                        .extracting(v -> v.getPropertyPath().toString()).containsExactly("valor_recebido");
            }
        }
    }
}
