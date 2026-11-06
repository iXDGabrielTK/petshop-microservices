/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.checkout.infrastructure.web.dto;

import java.math.BigDecimal;
import java.util.List;

public record ReciboResponse(
        String id,
        String dataVenda,
        BigDecimal total,
        List<ItemReciboResponse> itens
) {}
