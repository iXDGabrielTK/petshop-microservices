package inv.dto;

import java.math.BigDecimal;

public record EstoqueBaixoMessage(
        String nomeProduto,
        BigDecimal estoqueAtual,
        BigDecimal estoqueMinimo
) {}