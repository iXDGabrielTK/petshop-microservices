package inv.shared.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Evento de integração publicado pelo Checkout e consumido pelo Finance
 * para criação de lançamentos contábeis no Ledger.
 */
public record VendaPagaEvent(
        Long vendaId,
        UUID uuidReferencia,
        BigDecimal valorRecebido
) {
}
