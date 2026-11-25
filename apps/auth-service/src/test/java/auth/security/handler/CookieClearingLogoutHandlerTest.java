/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package auth.security.handler;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CookieClearingLogoutHandler — Testes Unitários de Expiração de Cookies no Logout")
class CookieClearingLogoutHandlerTest {

    @Mock
    private OAuth2AuthorizationService authorizationService;

    @InjectMocks
    private CookieClearingLogoutHandler logoutHandler;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(logoutHandler, "isCookieSecure", true);
        ReflectionTestUtils.setField(logoutHandler, "cookieSameSite", "Lax");
    }

    @Test
    @DisplayName("Deve adicionar cookies de limpeza para refresh_token e JSESSIONID com maxAge 0")
    void deveLimparCookiesNoLogout() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication authentication = mock(Authentication.class);

        logoutHandler.logout(request, response, authentication);

        // Verifica o cabeçalho Set-Cookie do refresh_token
        String setCookieHeader = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookieHeader).isNotNull();
        assertThat(setCookieHeader).contains("refresh_token=");
        assertThat(setCookieHeader).contains("Max-Age=0");
        assertThat(setCookieHeader).contains("Path=/oauth2/token");
        assertThat(setCookieHeader).contains("HttpOnly");
        assertThat(setCookieHeader).contains("Secure");
        assertThat(setCookieHeader).contains("SameSite=Lax");

        // Verifica o cookie JSESSIONID
        Cookie jsessionCookie = response.getCookie("JSESSIONID");
        assertThat(jsessionCookie).isNotNull();
        assertThat(jsessionCookie.getPath()).isEqualTo("/");
        assertThat(jsessionCookie.getMaxAge()).isEqualTo(0);
        verifyNoInteractions(authorizationService);
    }

    @Test
    @DisplayName("Deve revogar autorização no servidor quando cookie refresh_token estiver presente e existir no OAuth2AuthorizationService")
    void deveRevogarAutorizacaoQuandoCookieRefreshTokenPresente() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("refresh_token", "rt-segredo-123"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2Authorization authorization = mock(OAuth2Authorization.class);
        when(authorizationService.findByToken("rt-segredo-123", OAuth2TokenType.REFRESH_TOKEN))
                .thenReturn(authorization);

        logoutHandler.logout(request, response, null);

        verify(authorizationService).findByToken("rt-segredo-123", OAuth2TokenType.REFRESH_TOKEN);
        verify(authorizationService).remove(authorization);
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
    }

    @Test
    @DisplayName("Deve limpar cookies sem falhar quando cookie refresh_token não corresponder a autorização ativa")
    void deveLimparCookiesQuandoRefreshTokenNaoEncontrado() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("refresh_token", "rt-inexistente"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(authorizationService.findByToken("rt-inexistente", OAuth2TokenType.REFRESH_TOKEN))
                .thenReturn(null);

        logoutHandler.logout(request, response, null);

        verify(authorizationService).findByToken("rt-inexistente", OAuth2TokenType.REFRESH_TOKEN);
        verify(authorizationService, never()).remove(org.mockito.ArgumentMatchers.any());
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
    }
}
