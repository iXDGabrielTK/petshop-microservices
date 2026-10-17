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