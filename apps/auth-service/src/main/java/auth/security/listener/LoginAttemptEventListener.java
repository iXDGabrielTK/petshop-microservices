/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package auth.security.listener;

import auth.security.LoginAttemptService;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Listener de eventos de autenticação do Spring Security para contabilizar falhas
 * de credenciais e limpar contadores após login bem-sucedido.
 */
@Component
public class LoginAttemptEventListener {

    private final LoginAttemptService loginAttemptService;

    public LoginAttemptEventListener(LoginAttemptService loginAttemptService) {
        this.loginAttemptService = Objects.requireNonNull(loginAttemptService, "loginAttemptService não pode ser nulo");
    }

    @EventListener
    public void onAuthenticationFailure(AuthenticationFailureBadCredentialsEvent event) {
        if (event == null || !(event.getAuthentication() instanceof UsernamePasswordAuthenticationToken auth)) {
            return;
        }
        loginAttemptService.recordFailure(auth.getName(), extractIp(auth));
    }

    @EventListener
    public void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        if (event == null || !(event.getAuthentication() instanceof UsernamePasswordAuthenticationToken auth)) {
            return;
        }
        loginAttemptService.recordSuccess(auth.getName(), extractIp(auth));
    }

    private static String extractIp(Authentication auth) {
        if (auth.getDetails() instanceof WebAuthenticationDetails details) {
            return details.getRemoteAddress();
        }
        return null;
    }
}
