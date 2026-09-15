package inv.checkout.infrastructure.messaging.event;

import inv.checkout.infrastructure.web.dto.VendaStatusResponse;

public record VendaAtualizadaEvent(
        Long vendaId,
        VendaStatusResponse snapshot
) {}