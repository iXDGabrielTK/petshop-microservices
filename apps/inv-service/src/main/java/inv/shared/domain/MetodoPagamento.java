/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.domain;

import lombok.Getter;

@Getter
public enum MetodoPagamento {
    DINHEIRO(false),
    PIX(false),
    CARTAO_CREDITO(true),
    CARTAO_DEBITO(true);

    private final boolean exigeProcessamento;

    MetodoPagamento(boolean exigeProcessamento) {
        this.exigeProcessamento = exigeProcessamento;
    }
}
