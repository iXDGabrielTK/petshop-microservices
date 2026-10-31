package gateway.filter;

import common.telemetry.TelemetriaKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ActivityFilter implements GlobalFilter {

    private static final Logger log = LoggerFactory.getLogger(ActivityFilter.class);

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private static final String ACTIVITY_KEY = TelemetriaKeys.ATIVIDADE_ZSET;
    private static final long LOG_SUPPRESSION_INTERVAL_MS = 10_000L;
    private final AtomicLong lastLogTimestamp = new AtomicLong(0);

    public ActivityFilter(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate não pode ser nulo");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Objects.requireNonNull(exchange, "exchange não pode ser nulo");
        Objects.requireNonNull(chain, "chain não pode ser nulo");

        long now = Instant.now().toEpochMilli();

        return redisTemplate.opsForZSet()
                .add(ACTIVITY_KEY, UUID.randomUUID().toString(), (double) now)
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