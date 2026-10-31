package gateway.telemetry;

import common.telemetry.TelemetriaKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

@Component
public class ActivityPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(ActivityPurgeScheduler.class);

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private static final String ACTIVITY_KEY = TelemetriaKeys.ATIVIDADE_ZSET;
    private static final long ONE_HOUR_IN_MS = 3_600_000L;

    public ActivityPurgeScheduler(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate não pode ser nulo");
    }

    @Scheduled(fixedRate = 300_000) // Executa periodicamente a cada 5 minutos
    public void purgeOldActivity() {
        long umaHoraAtras = Instant.now().toEpochMilli() - ONE_HOUR_IN_MS;

        redisTemplate.opsForZSet()
                .removeRangeByScore(ACTIVITY_KEY, Range.closed(0.0, (double) umaHoraAtras))
                .doOnNext(removed -> {
                    if (removed != null && removed > 0) {
                        log.debug("Purga de telemetria concluída: {} registros removidos", removed);
                    }
                })
                .doOnError(e -> log.warn("Falha na execução periódica da purga de telemetria Redis: {}", e.getMessage()))
                .onErrorComplete()
                .subscribe();
    }
}
