/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.outbox.metrics;

import inv.shared.outbox.model.Outbox;
import inv.shared.outbox.repository.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

@Component
public class OutboxMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    public static final String METRIC_OLDEST_ELIGIBLE_AGE_SECONDS = "outbox.oldest.eligible.age.seconds";
    public static final String METRIC_QUARANTINED_COUNT = "outbox.quarantined.count";
    public static final String METRIC_QUARANTINED_TOTAL = "outbox.quarantined.total";
    public static final String METRIC_REPLAY_REQUESTED_TOTAL = "outbox.replay.requested.total";
    public static final String METRIC_REPLAY_COMPLETED_TOTAL = "outbox.replay.completed.total";

    private final OutboxRepository outboxRepository;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Counter replayRequestedCounter;
    private final Counter replayCompletedCounter;

    private volatile double lastOldestEligibleAgeSeconds = Double.NaN;
    private volatile double lastQuarantinedCount = Double.NaN;

    public OutboxMetrics(
            OutboxRepository outboxRepository,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.outboxRepository = outboxRepository;
        this.meterRegistry = meterRegistry;
        this.clock = clock;

        Gauge.builder(METRIC_OLDEST_ELIGIBLE_AGE_SECONDS, this, OutboxMetrics::readOldestEligibleAgeSeconds)
                .description("Idade em segundos do registro elegivel mais antigo da outbox")
                .strongReference(true)
                .register(meterRegistry);

        Gauge.builder(METRIC_QUARANTINED_COUNT, this, OutboxMetrics::readQuarantinedCount)
                .description("Quantidade atual de registros da outbox em quarentena")
                .strongReference(true)
                .register(meterRegistry);

        this.replayRequestedCounter = Counter.builder(METRIC_REPLAY_REQUESTED_TOTAL)
                .description("Total de pedidos 200 OK em POST /admin/outbox/{id}/replay")
                .register(meterRegistry);

        this.replayCompletedCounter = Counter.builder(METRIC_REPLAY_COMPLETED_TOTAL)
                .description("Total de registros da outbox reprocessados via replay entregues e concluidos")
                .register(meterRegistry);
    }

    double readOldestEligibleAgeSeconds() {
        try {
            LocalDateTime agora = LocalDateTime.now(clock);
            BigDecimal age = outboxRepository.findOldestEligibleAgeSeconds(agora);
            double value = (age == null) ? 0.0d : Math.max(0.0d, age.doubleValue());
            lastOldestEligibleAgeSeconds = value;
            return value;
        } catch (RuntimeException e) {
            log.warn("Falha transitória ao consultar métrica {}: {}", METRIC_OLDEST_ELIGIBLE_AGE_SECONDS, e.getMessage());
            return lastOldestEligibleAgeSeconds;
        }
    }

    double readQuarantinedCount() {
        try {
            double value = Math.max(0L, outboxRepository.countByStatus(Outbox.STATUS_QUARANTINED));
            lastQuarantinedCount = value;
            return value;
        } catch (RuntimeException e) {
            log.warn("Falha transitória ao consultar métrica {}: {}", METRIC_QUARANTINED_COUNT, e.getMessage());
            return lastQuarantinedCount;
        }
    }

    public void recordQuarantined(String errorType) {
        String safeTag = (errorType == null || errorType.isBlank()) ? "UNKNOWN_ERROR" : errorType;
        Counter.builder(METRIC_QUARANTINED_TOTAL)
                .description("Total de registros da outbox movidos para quarentena")
                .tag("error_type", safeTag)
                .register(meterRegistry)
                .increment();
    }

    public void recordReplayRequested() {
        replayRequestedCounter.increment();
    }

    public void recordReplayCompleted() {
        replayCompletedCounter.increment();
    }
}
