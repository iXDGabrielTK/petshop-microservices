package inv.finance.infrastructure.messaging;

import inv.finance.domain.model.TipoLancamento;
import java.math.BigDecimal;

public record LancamentoRegistradoEvent(
        Long lancamentoId,
        Long vendaId,
        BigDecimal valorAssinado,
        TipoLancamento tipo
) {}