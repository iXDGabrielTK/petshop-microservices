/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.finance.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

public record FechamentoCaixaView(
        LocalDate dataReferencia,
        BigDecimal saldoFinal,
        long highWatermark,
        LocalDateTime processadoEm
) {
    public FechamentoCaixaView {
        Objects.requireNonNull(dataReferencia, "dataReferencia");
        Objects.requireNonNull(saldoFinal, "saldoFinal");
    }
}
