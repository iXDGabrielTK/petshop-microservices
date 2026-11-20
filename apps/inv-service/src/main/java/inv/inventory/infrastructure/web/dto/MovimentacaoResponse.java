/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.inventory.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import inv.inventory.domain.model.TipoMovimentacao;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record MovimentacaoResponse(
        Long id, Long produtoId, String produtoNome, String codigoBarras,
        Long vendaId, TipoMovimentacao tipo, BigDecimal quantidade,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDateTime dataHora, String observacao
) {}
