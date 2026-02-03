package mail.message;

import java.math.BigDecimal;

public record EstoqueBaixoMessage(
        int version,
        String eventId,
        String nomeProduto,
        BigDecimal estoqueAtual,
        BigDecimal estoqueMinimo
) {}