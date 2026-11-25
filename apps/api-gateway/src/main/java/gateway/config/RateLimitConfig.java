/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Objects;

@Configuration
public class RateLimitConfig {

    public static final String ANONYMOUS_KEY = "anonymous";
    public static final String ANONYMOUS_PREFIX = "anon_";

    private final int trustedProxyHops;

    public RateLimitConfig(@Value("${gateway.rate-limit.trusted-proxy-hops:0}") int trustedProxyHops) {
        if (trustedProxyHops < 0) {
            throw new IllegalArgumentException("gateway.rate-limit.trusted-proxy-hops não pode ser negativo");
        }
        this.trustedProxyHops = trustedProxyHops;
    }

    @Bean
    public KeyResolver userKeyResolver() {
        RemoteAddressResolver addressResolver = trustedProxyHops >= 1
                ? XForwardedRemoteAddressResolver.maxTrustedIndex(trustedProxyHops)
                : null;

        return exchange -> {
            if (exchange == null || exchange.getRequest() == null) {
                return Mono.just(ANONYMOUS_KEY);
            }

            InetSocketAddress resolvedAddress = addressResolver != null
                    ? addressResolver.resolve(exchange)
                    : exchange.getRequest().getRemoteAddress();

            String ip = extractIp(resolvedAddress);
            if (ip == null && addressResolver != null) {
                ip = extractIp(exchange.getRequest().getRemoteAddress());
            }
            if (ip != null) {
                return Mono.just(ip);
            }

            // Mitigação de DoS: se não houver IP determinável, particiona por rota e User-Agent
            String userAgent = exchange.getRequest().getHeaders().getFirst("User-Agent");
            String path = exchange.getRequest().getPath() != null ? exchange.getRequest().getPath().value() : "";
            if ((userAgent != null && !userAgent.isBlank()) || !path.isBlank()) {
                String partition = Integer.toHexString(Objects.hash(userAgent, path));
                return Mono.just(ANONYMOUS_PREFIX + partition);
            }

            return Mono.just(ANONYMOUS_KEY);
        };
    }

    private static String extractIp(InetSocketAddress address) {
        if (address == null) {
            return null;
        }
        String ip = address.getAddress() != null
                ? address.getAddress().getHostAddress()
                : address.getHostString();
        if (ip == null || ip.isBlank()) {
            return null;
        }
        return ip.trim();
    }
}
