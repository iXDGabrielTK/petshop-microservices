/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.finance.infrastructure.web.dto;

import java.math.BigDecimal;

public interface SnapshotResult {
    long getUltimoLancamentoId();
    BigDecimal getCreditos();
    BigDecimal getDebitos();
}
