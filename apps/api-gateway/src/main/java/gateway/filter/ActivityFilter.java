package gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Component
public class ActivityFilter implements GlobalFilter {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private static final String ACTIVITY_KEY = "system:activity:ts";

    public ActivityFilter(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain){

        long now = Instant.now().toEpochMilli();
        long umaHoraAtras = now - 3600000;

        return redisTemplate.opsForZSet()
                .add(ACTIVITY_KEY, UUID.randomUUID().toString(), (double) now)
                .then(redisTemplate.opsForZSet()
                        .removeRangeByScore(
                                ACTIVITY_KEY,
                                Range.closed(0.0, (double) umaHoraAtras)
                        ))
                .then(chain.filter(exchange));
    }
}