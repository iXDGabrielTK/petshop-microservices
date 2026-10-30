package gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Optional;

@Configuration
public class RateLimitConfig {

    public static final String ANONYMOUS_KEY = "anonymous";

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            if (exchange == null || exchange.getRequest() == null) {
                return Mono.just(ANONYMOUS_KEY);
            }
            return Mono.just(
                    Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                            .map(address -> {
                                if (address.getAddress() != null) {
                                    return address.getAddress().getHostAddress();
                                }
                                return address.getHostString();
                            })
                            .filter(ip -> !ip.isBlank())
                            .orElse(ANONYMOUS_KEY)
            );
        };
    }
}