/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.finance.infrastructure.messaging;

import inv.finance.domain.model.TipoLancamento;
import java.math.BigDecimal;

public record LancamentoRegistradoEvent(
        Long lancamentoId,
        Long vendaId,
        BigDecimal valorAssinado,
        TipoLancamento tipo
) {}
