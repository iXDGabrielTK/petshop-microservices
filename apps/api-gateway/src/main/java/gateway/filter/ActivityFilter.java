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
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ActivityFilter implements GlobalFilter, Ordered {

    public static final int FILTER_ORDER = 1000;

    private static final Logger log = LoggerFactory.getLogger(ActivityFilter.class);
    private static final long LOG_SUPPRESSION_INTERVAL_MS = 10_000L;

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final Clock clock;
    private final AtomicLong lastLogTimestamp = new AtomicLong(0);

    @Autowired
    public ActivityFilter(ReactiveRedisTemplate<String, String> redisTemplate) {
        this(redisTemplate, Clock.systemUTC());
    }

    ActivityFilter(ReactiveRedisTemplate<String, String> redisTemplate, Clock clock) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate não pode ser nulo");
        this.clock = Objects.requireNonNull(clock, "clock não pode ser nulo");
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

        return redisTemplate.opsForValue()
                .increment(bucketKey)
                .flatMap(ignored -> redisTemplate.expire(bucketKey, TelemetriaKeys.ATIVIDADE_BUCKET_TTL))
                .onErrorResume(e -> {
                    logTelemetryError(e);
                    return Mono.empty();
                })
                .then(chain.filter(exchange));
    }

    private void logTelemetryError(Throwable e) {
        long current = System.currentTimeMillis();
        long last = lastLogTimestamp.get();
        if (current - last > LOG_SUPPRESSION_INTERVAL_MS) {
            if (lastLogTimestamp.compareAndSet(last, current)) {
                log.warn("Falha na telemetria de atividade Redis (não bloqueante): {}", e.getMessage());
            }
        }
    }
}
