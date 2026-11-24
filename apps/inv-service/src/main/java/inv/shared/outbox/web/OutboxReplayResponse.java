/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.outbox.web;

import java.time.LocalDateTime;

public record OutboxReplayResponse(
        Long id,
        String status,
        LocalDateTime replayedAt,
        int replayCount) {
}
