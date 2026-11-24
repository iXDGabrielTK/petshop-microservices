/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.outbox.scheduler;

record OutboxAttemptSnapshot(
        Long id,
        int attempts,
        String exchange,
        String routingKey,
        String eventType,
        int version,
        int replayCount,
        String messageId,
        String correlationId) {

    OutboxAttemptSnapshot(Long id, int attempts, String exchange, String routingKey) {
        this(id, attempts, exchange, routingKey, null, 0, 0, null, null);
    }

    OutboxAttemptSnapshot withDispatchIds(String messageId, String correlationId) {
        return new OutboxAttemptSnapshot(
                id, attempts, exchange, routingKey, eventType, version, replayCount, messageId, correlationId);
    }
}
