package gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Objects;

@Configuration
public class RateLimitConfig {

    public static final String ANONYMOUS_KEY = "anonymous";
    public static final String ANONYMOUS_PREFIX = "anon_";

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            if (exchange == null || exchange.getRequest() == null) {
                return Mono.just(ANONYMOUS_KEY);
            }

            // 1. Prioriza cabeçalhos de proxy reverso (X-Forwarded-For e X-Real-IP)
            String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                for (String part : xff.split(",")) {
                    String clientIp = part.trim();
                    if (!clientIp.isBlank()) {
                        return Mono.just(clientIp);
                    }
                }
            }

            String xRealIp = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
            if (xRealIp != null && !xRealIp.isBlank()) {
                return Mono.just(xRealIp.trim());
            }

            // 2. Fallback para remoteAddress direto do socket
            InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
            if (remoteAddress != null) {
                String ip = remoteAddress.getAddress() != null
                        ? remoteAddress.getAddress().getHostAddress()
                        : remoteAddress.getHostString();
                if (ip != null && !ip.isBlank()) {
                    return Mono.just(ip.trim());
                }
            }

            // 3. Mitigação de DoS: se não houver IP determinável, particiona por rota e User-Agent
            String userAgent = exchange.getRequest().getHeaders().getFirst("User-Agent");
            String path = exchange.getRequest().getPath() != null ? exchange.getRequest().getPath().value() : "";
            if ((userAgent != null && !userAgent.isBlank()) || !path.isBlank()) {
                String partition = Integer.toHexString(Objects.hash(userAgent, path));
                return Mono.just(ANONYMOUS_PREFIX + partition);
            }

            return Mono.just(ANONYMOUS_KEY);
        };
    }
}