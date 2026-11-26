/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package auth.security;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Resolve o endereço IP do cliente de forma resiliente a spoofing de cabeçalhos de proxy.
 * <p>
 * Desembrulha wrappers (como {@code ForwardedHeaderFilter}) antes de inspecionar o socket real
 * e aplica {@code auth.login.lockout.trusted-proxy-hops} (padrão {@code 0}) pela direita da cadeia
 * {@code X-Forwarded-For} apenas quando houver proxies confiáveis configurados.
 */
@Component
public class ClientIpResolver {

    public static final String UNKNOWN_IP = "unknown";

    private final int trustedProxyHops;

    @Autowired
    public ClientIpResolver(@Value("${auth.login.lockout.trusted-proxy-hops:0}") int trustedProxyHops) {
        if (trustedProxyHops < 0) {
            throw new IllegalArgumentException("auth.login.lockout.trusted-proxy-hops não pode ser negativo");
        }
        this.trustedProxyHops = trustedProxyHops;
    }

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN_IP;
        }

        ServletRequest raw = unwrap(request);
        String socketIp = normalizeIp(raw.getRemoteAddr());

        if (trustedProxyHops == 0) {
            return socketIp != null ? socketIp : UNKNOWN_IP;
        }

        if (raw instanceof HttpServletRequest rawHttp) {
            List<String> xffHeaders = Collections.list(rawHttp.getHeaders("X-Forwarded-For"));
            if (xffHeaders.size() == 1) {
                String resolved = resolveFromXForwardedFor(xffHeaders.get(0));
                if (resolved != null) {
                    return resolved;
                }
            }
        }

        return socketIp != null ? socketIp : UNKNOWN_IP;
    }

    private String resolveFromXForwardedFor(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        String[] parts = headerValue.split(",", -1);
        int index = Math.max(0, parts.length - trustedProxyHops);
        return normalizeIp(parts[index]);
    }

    private static ServletRequest unwrap(ServletRequest request) {
        ServletRequest current = request;
        while (current instanceof ServletRequestWrapper wrapper && wrapper.getRequest() != null) {
            current = wrapper.getRequest();
        }
        return current;
    }

    private static String normalizeIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        return ip.trim();
    }
}
