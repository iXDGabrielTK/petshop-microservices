package gateway.filter;

import common.telemetry.TelemetriaKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Component
public class ActivityFilter implements GlobalFilter {

    private static final Logger log = LoggerFactory.getLogger(ActivityFilter.class);

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private static final String ACTIVITY_KEY = TelemetriaKeys.ATIVIDADE_ZSET;

    public ActivityFilter(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate não pode ser nulo");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Objects.requireNonNull(exchange, "exchange não pode ser nulo");
        Objects.requireNonNull(chain, "chain não pode ser nulo");

        long now = Instant.now().toEpochMilli();
        long umaHoraAtras = now - 3600000;

        return redisTemplate.opsForZSet()
                .add(ACTIVITY_KEY, UUID.randomUUID().toString(), (double) now)
                .then(redisTemplate.opsForZSet()
                        .removeRangeByScore(
                                ACTIVITY_KEY,
                                Range.closed(0.0, (double) umaHoraAtras)
                        ))
                .onErrorResume(e -> {
                    log.warn("Falha na telemetria de atividade Redis (não bloqueante): {}", e.getMessage());
                    return Mono.empty();
                })
                .then(chain.filter(exchange));
    }
}