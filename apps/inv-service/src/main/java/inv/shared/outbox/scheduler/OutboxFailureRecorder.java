/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.outbox.scheduler;

import inv.shared.outbox.metrics.OutboxMetrics;
import inv.shared.outbox.model.Outbox;
import inv.shared.outbox.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

@Component
class OutboxFailureRecorder {

    private static final Logger log = LoggerFactory.getLogger(OutboxFailureRecorder.class);
    private static final int MAX_ERROR_TYPE_LENGTH = 64;

    private final OutboxRepository outboxRepository;
    private final OutboxMetrics outboxMetrics;
    private final Clock clock;
    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;

    OutboxFailureRecorder(
            OutboxRepository outboxRepository,
            OutboxMetrics outboxMetrics,
            Clock clock,
            @Value("${outbox.retry.max-attempts:5}") int maxAttempts,
            @Value("${outbox.retry.base-delay-ms:2000}") long baseDelayMs,
            @Value("${outbox.retry.max-delay-ms:60000}") long maxDelayMs) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("outbox.retry.max-attempts deve ser maior que zero.");
        }
        if (baseDelayMs <= 0) {
            throw new IllegalArgumentException("outbox.retry.base-delay-ms deve ser maior que zero.");
        }
        if (maxDelayMs < baseDelayMs) {
            throw new IllegalArgumentException("outbox.retry.max-delay-ms deve ser maior ou igual a base-delay-ms.");
        }
        this.outboxRepository = outboxRepository;
        this.outboxMetrics = outboxMetrics;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordFailure(
            OutboxAttemptSnapshot snapshot,
            OutboxFailureCategory category,
            String errorType,
            String errorMessage) {
        LocalDateTime agora = LocalDateTime.now(clock);
        int nextAttempts = snapshot.attempts() + 1;
        boolean quarantine = (category == OutboxFailureCategory.POISON_PILL) || (nextAttempts >= maxAttempts);
        String novoStatus = quarantine ? Outbox.STATUS_QUARANTINED : Outbox.STATUS_PENDING;
        LocalDateTime nextAttemptAt = quarantine
                ? null
                : agora.plus(Duration.ofMillis(calculateBackoffMs(nextAttempts)));
        LocalDateTime quarantinedAt = quarantine ? agora : null;

        String safeErrorType = sanitizeErrorType(errorType);
        String safeErrorMessage = (errorMessage == null || errorMessage.isBlank()) ? safeErrorType : errorMessage;

        int updated = outboxRepository.recordFailure(
                snapshot.id(),
                novoStatus,
                nextAttempts,
                nextAttemptAt,
                agora,
                safeErrorType,
                safeErrorMessage,
                quarantinedAt);

        if (updated == 0) {
            log.debug("Registro outbox ID {} já não está PENDING; falha pós-rollback ignorada (no-op).", snapshot.id());
            return;
        }

        if (quarantine) {
            outboxMetrics.recordQuarantined(safeErrorType);
            log.warn(
                    "Outbox ID {} movido para QUARANTINED (category={}, errorType={}, attempts={}, exchange={}, routingKey={}).",
                    snapshot.id(), category, safeErrorType, nextAttempts, snapshot.exchange(), snapshot.routingKey());
        } else {
            log.info(
                    "Outbox ID {} agendado para retry (attempts={}/{}, nextAttemptAt={}, category={}, errorType={}).",
                    snapshot.id(), nextAttempts, maxAttempts, nextAttemptAt, category, safeErrorType);
        }
    }

    long calculateBackoffMs(int attempts) {
        int exponent = Math.max(0, attempts - 1);
        long delay = baseDelayMs;
        for (int i = 0; i < exponent; i++) {
            if (delay >= maxDelayMs || delay > maxDelayMs / 2) {
                return maxDelayMs;
            }
            delay *= 2;
        }
        return Math.min(delay, maxDelayMs);
    }

    private static String sanitizeErrorType(String errorType) {
        if (errorType == null || errorType.isBlank()) {
            return "UNKNOWN_ERROR";
        }
        return errorType.length() <= MAX_ERROR_TYPE_LENGTH
                ? errorType
                : errorType.substring(0, MAX_ERROR_TYPE_LENGTH);
    }
}
