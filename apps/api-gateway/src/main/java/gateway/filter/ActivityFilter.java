/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package gateway.filter;

import common.telemetry.TelemetriaKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ActivityFilter implements GlobalFilter, Ordered {

    public static final int FILTER_ORDER = 1000;
    static final Duration DEFAULT_TELEMETRY_TIMEOUT = Duration.ofMillis(200);

    private static final Logger log = LoggerFactory.getLogger(ActivityFilter.class);
    private static final long LOG_SUPPRESSION_INTERVAL_MS = 10_000L;
    private static final long UNSET_LOG_TIMESTAMP = Long.MIN_VALUE;
    private static final RedisScript<Long> INCREMENT_WITH_TTL_SCRIPT =
            new DefaultRedisScript<>(TelemetriaKeys.ATIVIDADE_INCREMENT_LUA, Long.class);
    private static final String BUCKET_TTL_SECONDS =
            String.valueOf(TelemetriaKeys.ATIVIDADE_BUCKET_TTL.toSeconds());

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final Clock clock;
    private final Duration telemetryTimeout;
    private final AtomicLong lastLogTimestamp = new AtomicLong(UNSET_LOG_TIMESTAMP);

    @Autowired
    public ActivityFilter(ReactiveRedisTemplate<String, String> redisTemplate) {
        this(redisTemplate, Clock.systemUTC(), DEFAULT_TELEMETRY_TIMEOUT);
    }

    ActivityFilter(ReactiveRedisTemplate<String, String> redisTemplate, Clock clock) {
        this(redisTemplate, clock, DEFAULT_TELEMETRY_TIMEOUT);
    }

    ActivityFilter(ReactiveRedisTemplate<String, String> redisTemplate, Clock clock, Duration telemetryTimeout) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate não pode ser nulo");
        this.clock = Objects.requireNonNull(clock, "clock não pode ser nulo");
        this.telemetryTimeout = Objects.requireNonNull(telemetryTimeout, "telemetryTimeout não pode ser nulo");
        if (telemetryTimeout.isNegative() || telemetryTimeout.isZero()) {
            throw new IllegalArgumentException("telemetryTimeout deve ser positivo");
        }
    }

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Objects.requireNonNull(exchange, "exchange não pode ser nulo");
        Objects.requireNonNull(chain, "chain não pode ser nulo");

        String bucketKey = TelemetriaKeys.atividadeBucketKey(clock.instant());

        return redisTemplate.execute(
                        INCREMENT_WITH_TTL_SCRIPT,
                        List.of(bucketKey),
                        List.of(BUCKET_TTL_SECONDS))
                .next()
                .timeout(telemetryTimeout)
                .onErrorResume(e -> {
                    logTelemetryError(e);
                    return Mono.empty();
                })
                .then(chain.filter(exchange));
    }

    private void logTelemetryError(Throwable e) {
        long current = clock.millis();
        long last = lastLogTimestamp.get();
        if (last == UNSET_LOG_TIMESTAMP || current - last > LOG_SUPPRESSION_INTERVAL_MS) {
            if (lastLogTimestamp.compareAndSet(last, current)) {
                log.warn("Falha na telemetria de atividade Redis (não bloqueante): {}", e.getMessage());
            }
        }
    }
}
