package inv.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ReciboResponse(
        String mensagem,
        BigDecimal valorTotal,
        LocalDateTime data
) {}