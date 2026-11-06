/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Evento compartilhado de integração publicado pelo Checkout após o cancelamento
 * de uma venda concluída e consumido pelo Finance para realização de estorno contábil.
 */
public record VendaEstornadaEvent(
        Long vendaId,
        UUID uuidReferenciaEstorno,
        BigDecimal valorEstorno
) {}
