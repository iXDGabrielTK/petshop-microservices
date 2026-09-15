package inv.inventory.infrastructure.messaging;

import java.math.BigDecimal;

public record EstoqueBaixoMessage(
        int version,
        String eventId,
        String nomeProduto,
        BigDecimal estoqueAtual,
        BigDecimal estoqueMinimo
) {}