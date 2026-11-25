/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.outbox;

import java.util.Map;

/**
 * Contribuidor modular de tipos de eventos permitidos para publicação via Outbox.
 * Mantém o Shared Kernel isolado dos Bounded Contexts sem recorrer a {@code Class.forName} arbitrário.
 */
public interface OutboxEventTypeContributor {

    Map<String, Class<?>> eventTypes();
}
