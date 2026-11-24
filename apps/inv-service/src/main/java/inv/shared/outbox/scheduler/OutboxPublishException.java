/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.outbox.scheduler;

class OutboxPublishException extends RuntimeException {

    private final OutboxAttemptSnapshot snapshot;
    private final OutboxFailureCategory category;
    private final String errorType;

    OutboxPublishException(
            OutboxAttemptSnapshot snapshot,
            OutboxFailureCategory category,
            String errorType,
            String message) {
        super(message);
        this.snapshot = snapshot;
        this.category = category;
        this.errorType = errorType;
    }

    OutboxPublishException(
            OutboxAttemptSnapshot snapshot,
            OutboxFailureCategory category,
            String errorType,
            String message,
            Throwable cause) {
        super(message, cause);
        this.snapshot = snapshot;
        this.category = category;
        this.errorType = errorType;
    }

    OutboxAttemptSnapshot getSnapshot() {
        return snapshot;
    }

    OutboxFailureCategory getCategory() {
        return category;
    }

    String getErrorType() {
        return errorType;
    }
}
