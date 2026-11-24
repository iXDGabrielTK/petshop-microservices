/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.outbox.scheduler;

class OutboxPublishException extends RuntimeException {

    OutboxPublishException(String message) {
        super(message);
    }

    OutboxPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
