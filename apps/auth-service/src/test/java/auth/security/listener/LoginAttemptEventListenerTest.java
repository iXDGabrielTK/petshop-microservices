/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package auth.security.listener;

import auth.security.LoginAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("LoginAttemptEventListener — Testes Unitários de Eventos de Autenticação")
class LoginAttemptEventListenerTest {

    @Mock
    private LoginAttemptService loginAttemptService;

    private LoginAttemptEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new LoginAttemptEventListener(loginAttemptService);
    }

    @Test
    @DisplayName("Deve registrar falha ao receber AuthenticationFailureBadCredentialsEvent de UsernamePasswordAuthenticationToken")
    void deveRegistrarFalhaEmBadCredentials() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.10.25");

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("user@petshop.com", "errada");
        auth.setDetails(new WebAuthenticationDetails(request));

        listener.onAuthenticationFailure(new AuthenticationFailureBadCredentialsEvent(auth, new BadCredentialsException("bad")));

        verify(loginAttemptService).recordFailure("user@petshop.com", "192.168.10.25");
    }

    @Test
    @DisplayName("Deve limpar contador ao receber AuthenticationSuccessEvent de UsernamePasswordAuthenticationToken")
    void deveRegistrarSucessoEmAuthenticationSuccess() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.10.25");

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("user@petshop.com", "correta");
        auth.setDetails(new WebAuthenticationDetails(request));

        listener.onAuthenticationSuccess(new AuthenticationSuccessEvent(auth));

        verify(loginAttemptService).recordSuccess("user@petshop.com", "192.168.10.25");
    }

    @Test
    @DisplayName("Deve ignorar eventos que não sejam de UsernamePasswordAuthenticationToken")
    void deveIgnorarOutrosTiposDeAuthentication() {
        TestingAuthenticationToken token = new TestingAuthenticationToken("principal", "creds");

        listener.onAuthenticationFailure(new AuthenticationFailureBadCredentialsEvent(token, new BadCredentialsException("bad")));
        listener.onAuthenticationSuccess(new AuthenticationSuccessEvent(token));

        verifyNoInteractions(loginAttemptService);
    }
}
