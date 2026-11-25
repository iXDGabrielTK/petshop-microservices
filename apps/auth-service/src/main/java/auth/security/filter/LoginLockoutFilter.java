/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package auth.security.filter;

import auth.security.LoginAttemptService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final LoginAttemptService loginAttemptService;

    public LoginLockoutFilter(LoginAttemptService loginAttemptService) {
        this.loginAttemptService = Objects.requireNonNull(loginAttemptService, "loginAttemptService não pode ser nulo");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if ("POST".equalsIgnoreCase(request.getMethod()) && "/login".equals(resolveRelativePath(request))) {
            String username = request.getParameter("username");
            String ip = request.getRemoteAddr();
            if (loginAttemptService.isBlocked(username, ip)) {
                log.warn("Tentativa de login bloqueada temporariamente por excesso de falhas para IP {}", ip);
                response.sendRedirect(request.getContextPath() + "/login?error");
                return;
            }
        }

        filterChain.doFilter(request, response);
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
