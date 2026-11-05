package inv.shared.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Evento compartilhado de integração publicado pelo Checkout após o cancelamento
 * de uma venda concluída e consumido pelo Finance para realização de estorno contábil.
 */
public record VendaEstornadaEvent(
        Long vendaId,
        UUID uuidReferenciaEstorno,
        BigDecimal valorEstorno
) {}
