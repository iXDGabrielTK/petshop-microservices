/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.event;

import inv.shared.domain.MetodoPagamento;
import java.math.BigDecimal;
import java.util.UUID;

public record PagamentoConsolidadoEvent(
        Long vendaId,
        UUID transacaoGatewayId,
        BigDecimal valorRecebido,
        MetodoPagamento metodo
) {}
