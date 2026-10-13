package inv.dashboard.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configuração de Cache Caffeine para o módulo inv.dashboard (DEBT-ARCH-001 / Etapa E5 / Decisão Q5).
 * TTL de 15 a 30 segundos, coleta de métricas de hit/miss via Micrometer e tamanho máximo configurável.
 */
@Configuration
public class DashboardCacheConfig {

    private static final Logger log = LoggerFactory.getLogger(DashboardCacheConfig.class);
    public static final String CACHE_NAME = "dashboard";

    @Bean
    public CacheManager cacheManager(
            @Value("${dashboard.cache.ttl:15s}") Duration ttl,
            @Value("${dashboard.cache.max-size:100}") long maxSize
    ) {
        log.info("Inicializando Caffeine Cache para '{}' com TTL={} e maxSize={}", CACHE_NAME, ttl, maxSize);
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(CACHE_NAME);
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttl)
                .recordStats());
        return cacheManager;
    }
}
