/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.inventory.infrastructure.web.dto;

import inv.inventory.domain.model.UnidadeMedida;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ProdutoRequestTest {
    @Test
    void normalizaEANOpcionalEValidaFormatoNomeEPrecisao() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var valido = new ProdutoRequest(" \t ", "Ração", UnidadeMedida.UN,
                    new BigDecimal("9999999.999"), new BigDecimal("99999999.99"));
            assertThat(valido.codigoBarras()).isNull();
            assertThat(validator.validate(valido)).isEmpty();
            for (String ean : new String[]{"12345678", "12345678901234"}) {
                assertThat(validator.validateValue(ProdutoRequest.class, "codigoBarras", ean)).isEmpty();
            }
            for (String ean : new String[]{"1234567", "123456789012345", "1234567a"}) {
                assertThat(validator.validateValue(ProdutoRequest.class, "codigoBarras", ean)).isNotEmpty();
            }
            for (String nome : new String[]{"Ração\rseca", "Ração\nseca", "Ração\tseca"}) {
                assertThat(validator.validateValue(ProdutoRequest.class, "nome", nome)).isNotEmpty();
            }
            for (String valor : new String[]{"100000000", "1.001"}) {
                assertThat(validator.validateValue(ProdutoRequest.class, "precoVenda", new BigDecimal(valor))).isNotEmpty();
            }
            for (String valor : new String[]{"10000000", "1.0001"}) {
                assertThat(validator.validateValue(ProdutoRequest.class, "quantidadeEstoque", new BigDecimal(valor))).isNotEmpty();
            }
        }
    }
}
