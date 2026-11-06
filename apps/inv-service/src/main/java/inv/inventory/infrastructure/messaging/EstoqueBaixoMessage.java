/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.inventory.infrastructure.messaging;

import java.math.BigDecimal;

public record EstoqueBaixoMessage(
        int version,
        String eventId,
        String nomeProduto,
        BigDecimal estoqueAtual,
        BigDecimal estoqueMinimo
) {}
