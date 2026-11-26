/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package auth.security.handler;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

@Component
public class CookieClearingLogoutHandler implements LogoutHandler {

    private static final String BEARER_PREFIX = "Bearer ";

    private final OAuth2AuthorizationService authorizationService;

    @Value("${auth.cookie.secure}")
    private boolean isCookieSecure;

    @Value("${auth.cookie.same-site}")
    private String cookieSameSite;

    public CookieClearingLogoutHandler(@Lazy OAuth2AuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        revogarRefreshTokenSePresente(request);

        response.addHeader(HttpHeaders.SET_COOKIE, expiredRefreshCookie("/oauth2").toString());
        response.addHeader(HttpHeaders.SET_COOKIE, expiredRefreshCookie("/oauth2/token").toString());

        Cookie jsessionCookie = new Cookie("JSESSIONID", null);
        jsessionCookie.setPath("/");
        jsessionCookie.setMaxAge(0);
        response.addCookie(jsessionCookie);
    }

    private ResponseCookie expiredRefreshCookie(String path) {
        return ResponseCookie.from("refresh_token", "")
                .httpOnly(true)
                .secure(isCookieSecure)
                .sameSite(cookieSameSite)
                .path(path)
                .maxAge(0)
                .build();
    }

    private void revogarRefreshTokenSePresente(HttpServletRequest request) {
        if (request == null || authorizationService == null) {
            return;
        }
        boolean revogado = false;
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("refresh_token".equals(cookie.getName())) {
                    String refreshToken = cookie.getValue();
                    if (refreshToken != null && !refreshToken.isBlank()) {
                        OAuth2Authorization authorization = authorizationService.findByToken(
                                refreshToken, OAuth2TokenType.REFRESH_TOKEN);
                        if (authorization != null) {
                            authorizationService.remove(authorization);
                            revogado = true;
                        }
                    }
                }
            }
        }
        if (!revogado) {
            String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authorizationHeader != null && authorizationHeader.startsWith(BEARER_PREFIX)) {
                String accessToken = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
                if (!accessToken.isBlank()) {
                    OAuth2Authorization authorization = authorizationService.findByToken(
                            accessToken, OAuth2TokenType.ACCESS_TOKEN);
                    if (authorization != null) {
                        authorizationService.remove(authorization);
                    }
                }
            }
        }
    }
}
