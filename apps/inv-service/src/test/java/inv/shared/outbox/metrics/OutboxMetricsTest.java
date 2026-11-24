/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.outbox.metrics;

import inv.shared.config.DateTimeConfig;
import inv.shared.outbox.model.Outbox;
import inv.shared.outbox.repository.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxMetrics — Testes Unitários de Métricas Micrometer e Resiliência de Gauge")
class OutboxMetricsTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-11-24T15:00:00Z");

    @Mock
    private OutboxRepository outboxRepository;

    private Clock clock;
    private SimpleMeterRegistry meterRegistry;
    private OutboxMetrics outboxMetrics;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_INSTANT, DateTimeConfig.ZONE_SAO_PAULO);
        meterRegistry = new SimpleMeterRegistry();
        outboxMetrics = new OutboxMetrics(outboxRepository, meterRegistry, clock);
    }

    @Test
    @DisplayName("Gauge outbox.oldest.eligible.age.seconds DEVE refletir idade, nunca ser negativo e tolerar falha transitória DB")
    void gaugeIdadeMaisAntigoDeveClamparZeroETolerarErroBanco() {
        LocalDateTime agora = LocalDateTime.now(clock);
        Gauge gauge = meterRegistry.find(OutboxMetrics.METRIC_OLDEST_ELIGIBLE_AGE_SECONDS).gauge();
        assertThat(gauge).isNotNull();

        doThrow(new DataAccessResourceFailureException("DB indisponivel antes da 1a leitura"))
                .when(outboxRepository).findOldestEligibleAgeSeconds(agora);
        assertThat(gauge.value()).isNaN();

        doReturn(new BigDecimal("42.5")).when(outboxRepository).findOldestEligibleAgeSeconds(agora);
        assertThat(gauge.value()).isEqualTo(42.5d);

        doReturn(null).when(outboxRepository).findOldestEligibleAgeSeconds(agora);
        assertThat(gauge.value()).isZero();

        doReturn(new BigDecimal("-15.0")).when(outboxRepository).findOldestEligibleAgeSeconds(agora);
        assertThat(gauge.value()).isZero();

        doReturn(new BigDecimal("120.0")).when(outboxRepository).findOldestEligibleAgeSeconds(agora);
        assertThat(gauge.value()).isEqualTo(120.0d);

        doThrow(new DataAccessResourceFailureException("DB indisponivel apos amostra valida"))
                .when(outboxRepository).findOldestEligibleAgeSeconds(agora);
        assertThat(gauge.value()).isEqualTo(120.0d);
    }

    @Test
    @DisplayName("Gauge outbox.quarantined.count DEVE refletir contagem e tolerar falha transitória DB")
    void gaugeQuarentenaDeveRefletirContagemETolerarErroBanco() {
        Gauge gauge = meterRegistry.find(OutboxMetrics.METRIC_QUARANTINED_COUNT).gauge();
        assertThat(gauge).isNotNull();

        doThrow(new DataAccessResourceFailureException("Timeout DB"))
                .when(outboxRepository).countByStatus(Outbox.STATUS_QUARANTINED);
        assertThat(gauge.value()).isNaN();

        doReturn(3L).when(outboxRepository).countByStatus(Outbox.STATUS_QUARANTINED);
        assertThat(gauge.value()).isEqualTo(3.0d);

        doThrow(new DataAccessResourceFailureException("Erro transitorio"))
                .when(outboxRepository).countByStatus(Outbox.STATUS_QUARANTINED);
        assertThat(gauge.value()).isEqualTo(3.0d);
    }

    @Test
    @DisplayName("Counters de quarentena e replay DEVEM incrementar com tags corretas")
    void countersDeQuarentenaEReplayDevemIncrementarComTagsCorretas() {
        Counter reqCounter = meterRegistry.find(OutboxMetrics.METRIC_REPLAY_REQUESTED_TOTAL).counter();
        Counter compCounter = meterRegistry.find(OutboxMetrics.METRIC_REPLAY_COMPLETED_TOTAL).counter();
        assertThat(reqCounter).isNotNull();
        assertThat(compCounter).isNotNull();
        assertThat(reqCounter.count()).isZero();
        assertThat(compCounter.count()).isZero();

        outboxMetrics.recordQuarantined("NO_ROUTE");
        outboxMetrics.recordQuarantined("NO_ROUTE");
        outboxMetrics.recordQuarantined("  ");
        outboxMetrics.recordQuarantined(null);
        outboxMetrics.recordReplayRequested();
        outboxMetrics.recordReplayCompleted();

        assertThat(meterRegistry.find(OutboxMetrics.METRIC_QUARANTINED_TOTAL).tag("error_type", "NO_ROUTE").counter().count())
                .isEqualTo(2.0d);
        assertThat(meterRegistry.find(OutboxMetrics.METRIC_QUARANTINED_TOTAL).tag("error_type", "UNKNOWN_ERROR").counter().count())
                .isEqualTo(2.0d);
        assertThat(reqCounter.count()).isEqualTo(1.0d);
        assertThat(compCounter.count()).isEqualTo(1.0d);
    }
}
