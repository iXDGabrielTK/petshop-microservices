package inv.checkout.infrastructure.web.dto;

import java.math.BigDecimal;
import java.util.List;

public record ReciboResponse(
        String id,
        String dataVenda,
        BigDecimal total,
        List<ItemReciboResponse> itens
) {}