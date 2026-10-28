package inv.checkout.infrastructure.web.dto;

import java.math.BigDecimal;

public record ItemReciboResponse(
        String nomeProduto,
        BigDecimal quantidade,
        BigDecimal precoUnitario,
        BigDecimal subtotal
) {}