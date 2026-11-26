/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package auth.security.filter;

import auth.security.ClientIpResolver;
import auth.security.LoginAttemptService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/**
 * Filtro posicionado antes da autenticação por formulário que bloqueia tentativas de login
 * cuja combinação {@code (username, IP)} esteja em período de lockout temporário.
 * <p>
 * Responde com o mesmo redirecionamento {@code /login?error} de credenciais inválidas
 * para não vazar se o e-mail existe na base.
 */
public class LoginLockoutFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoginLockoutFilter.class);
    private static final RequestMatcher LOGIN_POST_MATCHER = AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/login");

    private final LoginAttemptService loginAttemptService;
    private final ClientIpResolver clientIpResolver;

    public LoginLockoutFilter(LoginAttemptService loginAttemptService, ClientIpResolver clientIpResolver) {
        this.loginAttemptService = Objects.requireNonNull(loginAttemptService, "loginAttemptService não pode ser nulo");
        this.clientIpResolver = Objects.requireNonNull(clientIpResolver, "clientIpResolver não pode ser nulo");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (isLoginPost(request)) {
            String username = request.getParameter("username");
            String ip = clientIpResolver.resolve(request);
            if (loginAttemptService.isBlocked(username, ip)) {
                log.warn("Tentativa de login bloqueada temporariamente por excesso de falhas para IP {}", ip);
                response.sendRedirect(request.getContextPath() + "/login?error");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private static boolean isLoginPost(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        if (LOGIN_POST_MATCHER.matches(request)) {
            return true;
        }
        String relativePath = resolveRelativePath(request);
        if (relativePath == null) {
            return false;
        }
        int matrixIdx = relativePath.indexOf(';');
        String cleanPath = matrixIdx >= 0 ? relativePath.substring(0, matrixIdx) : relativePath;
        return "/login".equals(cleanPath);
    }

    private static String resolveRelativePath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path != null && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }
}
