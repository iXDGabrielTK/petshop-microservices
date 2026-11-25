/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.outbox;

import inv.shared.event.VendaEstornadaEvent;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
class SharedOutboxEventTypes implements OutboxEventTypeContributor {

    @Override
    public Map<String, Class<?>> eventTypes() {
        return Map.of(VendaEstornadaEvent.class.getName(), VendaEstornadaEvent.class);
    }
}
