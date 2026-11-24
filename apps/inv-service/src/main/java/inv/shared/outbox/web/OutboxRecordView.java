/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.outbox.web;

import inv.shared.outbox.model.Outbox;

import java.time.LocalDateTime;

public record OutboxRecordView(
        Long id,
        String exchange,
        String routingKey,
        String eventType,
        int version,
        String payload,
        String status,
        int attempts,
        LocalDateTime createdAt,
        LocalDateTime nextAttemptAt,
        LocalDateTime lastAttemptAt,
        String lastErrorType,
        String lastError,
        LocalDateTime quarantinedAt,
        LocalDateTime replayedAt,
        int replayCount) {

    public static OutboxRecordView from(Outbox outbox) {
        return new OutboxRecordView(
                outbox.getId(),
                outbox.getExchange(),
                outbox.getRoutingKey(),
                outbox.getEventType(),
                outbox.getVersion(),
                outbox.getPayload(),
                outbox.getStatus(),
                outbox.getAttempts(),
                outbox.getCreatedAt(),
                outbox.getNextAttemptAt(),
                outbox.getLastAttemptAt(),
                outbox.getLastErrorType(),
                outbox.getLastError(),
                outbox.getQuarantinedAt(),
                outbox.getReplayedAt(),
                outbox.getReplayCount());
    }
}
