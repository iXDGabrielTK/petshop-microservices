/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.outbox.scheduler;

import inv.shared.outbox.metrics.OutboxMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);

    private final OutboxDeliveryStep deliveryStep;
    private final OutboxFailureRecorder failureRecorder;
    private final OutboxMetrics outboxMetrics;
    private final Clock clock;

    OutboxProcessor(
            OutboxDeliveryStep deliveryStep,
            OutboxFailureRecorder failureRecorder,
            OutboxMetrics outboxMetrics,
            Clock clock) {
        this.deliveryStep = deliveryStep;
        this.failureRecorder = failureRecorder;
        this.outboxMetrics = outboxMetrics;
        this.clock = clock;
    }

    public OutboxStepResult processNextStep() {
        LocalDateTime agora = LocalDateTime.now(clock);
        AtomicReference<OutboxAttemptSnapshot> attemptRef = new AtomicReference<>();

        try {
            boolean delivered = deliveryStep.deliverNext(agora, attemptRef);
            if (delivered) {
                OutboxAttemptSnapshot completed = attemptRef.get();
                if (completed != null && completed.replayCount() > 0) {
                    outboxMetrics.recordReplayCompleted();
                    log.info(
                            "event=outbox_replay_completed outboxId={} eventType={} version={} replayCount={} messageId={} correlationId={}",
                            completed.id(),
                            completed.eventType(),
                            completed.version(),
                            completed.replayCount(),
                            completed.messageId(),
                            completed.correlationId());
                }
                return OutboxStepResult.DELIVERED;
            }
            return OutboxStepResult.EMPTY;
        } catch (OutboxPublishException e) {
            OutboxAttemptSnapshot snapshot = e.getSnapshot() != null ? e.getSnapshot() : attemptRef.get();
            log.error(
                    "Falha ao processar outbox ID {} (exchange={}, routingKey={}, category={}, errorType={}): {}",
                    snapshot != null ? snapshot.id() : null,
                    snapshot != null ? snapshot.exchange() : null,
                    snapshot != null ? snapshot.routingKey() : null,
                    e.getCategory(),
                    e.getErrorType(),
                    e.getMessage());

            if (snapshot != null && !recordFailureSafely(snapshot, e.getCategory(), e.getErrorType(), e.getMessage())) {
                return OutboxStepResult.FAILED_PAUSE;
            }

            return (e.getCategory() == OutboxFailureCategory.POISON_PILL
                    || e.getCategory() == OutboxFailureCategory.ROUTING_OR_REJECTION)
                    ? OutboxStepResult.FAILED_CONTINUE
                    : OutboxStepResult.FAILED_PAUSE;
        } catch (RuntimeException e) {
            OutboxAttemptSnapshot snapshot = attemptRef.get();
            log.error(
                    "Erro inesperado ao processar outbox ID {} (exchange={}, routingKey={}): {}",
                    snapshot != null ? snapshot.id() : null,
                    snapshot != null ? snapshot.exchange() : null,
                    snapshot != null ? snapshot.routingKey() : null,
                    e.getMessage(),
                    e);

            if (snapshot != null) {
                recordFailureSafely(
                        snapshot,
                        OutboxFailureCategory.POST_CONFIRM_PERSISTENCE,
                        "POST_CONFIRM_PERSISTENCE_ERROR",
                        "Falha de persistência/commit pós-seleção: " + e.getMessage());
            }
            return OutboxStepResult.FAILED_PAUSE;
        }
    }

    public boolean processNext() {
        OutboxStepResult result = processNextStep();
        return result == OutboxStepResult.DELIVERED || result == OutboxStepResult.FAILED_CONTINUE;
    }

    private boolean recordFailureSafely(
            OutboxAttemptSnapshot snapshot,
            OutboxFailureCategory category,
            String errorType,
            String errorMessage) {
        try {
            failureRecorder.recordFailure(snapshot, category, errorType, errorMessage);
            return true;
        } catch (RuntimeException recEx) {
            log.error(
                    "Falha ao registrar diagnóstico na outbox ID {} após rollback: {}",
                    snapshot.id(), recEx.getMessage(), recEx);
            return false;
        }
    }
}
