/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.checkout.infrastructure.messaging;

import inv.checkout.infrastructure.messaging.event.PagamentoConfirmadoEvent;
import inv.shared.outbox.OutboxEventTypeContributor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
class CheckoutOutboxEventTypes implements OutboxEventTypeContributor {

    @Override
    public Map<String, Class<?>> eventTypes() {
        return Map.of(PagamentoConfirmadoEvent.class.getName(), PagamentoConfirmadoEvent.class);
    }
}
