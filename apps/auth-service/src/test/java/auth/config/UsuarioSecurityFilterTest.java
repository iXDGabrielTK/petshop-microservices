/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package auth.config;

import auth.controller.UsuarioController;
import auth.dto.request.UserSettingsRequest;
import auth.dto.response.UserResponse;
import auth.model.Theme;
import auth.security.ClientIpResolver;
import auth.security.LoginAttemptService;
import auth.security.filter.CookieRefreshTokenRequestFilter;
import auth.security.filter.RefreshTokenCookieFilter;
import auth.security.handler.CookieClearingLogoutHandler;
import auth.service.UsuarioService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import common.exception.GlobalExceptionHandler;
import common.security.RsaKeyUtils;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(classes = UsuarioSecurityFilterTest.TestContextConfig.class)
@WebAppConfiguration
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("UsuarioSecurityFilterTest — Cadeia de Filtros JWT em /usuarios/** e Proteção de /logout")
class UsuarioSecurityFilterTest {

    @TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({
            SecurityConfig.class,
            LoginAttemptService.class,
            ClientIpResolver.class,
            CookieRefreshTokenRequestFilter.class,
            RefreshTokenCookieFilter.class,
            CookieClearingLogoutHandler.class,
            UsuarioController.class,
            GlobalExceptionHandler.class
    })
    static class TestContextConfig {
    }

    private static final KeyPair KEY_PAIR = RsaKeyUtils.generateKeyPair();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String pubKey = Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded());
        String privKey = Base64.getEncoder().encodeToString(KEY_PAIR.getPrivate().getEncoded());
        registry.add("jwt.public.key", () -> pubKey);
        registry.add("jwt.private.key", () -> privKey);
        registry.add("frontend.base-url", () -> "http://localhost:5173");
        registry.add("cors.allowed-methods", () -> "GET,POST,PATCH,PUT,DELETE,OPTIONS");
        registry.add("cors.allowed-headers", () -> "Authorization,Content-Type");
        registry.add("cors.allow-credentials", () -> "true");
        registry.add("auth.cookie.secure", () -> "true");
        registry.add("auth.cookie.same-site", () -> "Lax");
    }

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private UsuarioService usuarioService;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private OAuth2AuthorizationService authorizationService;

    private MockMvc mockMvc;

    @BeforeAll
    void setUpOnce() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter((Filter) context.getBean("springSecurityFilterChain"))
                .build();
    }

    @BeforeEach
    void resetMocks() {
        Mockito.reset(usuarioService, authorizationService);
    }

    @Test
    @DisplayName("GET /usuarios/me deve autenticar via Bearer JWT e retornar 200 OK")
    void deveAutenticarMeViaJwt() throws Exception {
        String token = createValidToken("user@petshop.com", 42L);
        when(usuarioService.obterDadosUsuarioLogado("user@petshop.com"))
                .thenReturn(new UserResponse(42L, "User", "user@petshop.com", Set.of("ROLE_USER"), "LIGHT"));

        mockMvc.perform(get("/usuarios/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("user@petshop.com"));

        verify(usuarioService).obterDadosUsuarioLogado("user@petshop.com");
    }

    @Test
    @DisplayName("GET /usuarios/me deve retornar 401 Unauthorized com Bearer JWT inválido")
    void deveRejeitarMeComJwtInvalido() throws Exception {
        mockMvc.perform(get("/usuarios/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token-invalido"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(usuarioService);
    }

    @Test
    @DisplayName("PATCH /usuarios/settings deve autenticar via Bearer JWT sem exigir token CSRF")
    void deveAutenticarPatchSettingsViaJwtSemCsrf() throws Exception {
        String token = createValidToken("user@petshop.com", 42L);

        mockMvc.perform(patch("/usuarios/settings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"DARK\"}"))
                .andExpect(status().isNoContent());

        verify(usuarioService).updateSettings(42L, new UserSettingsRequest(Theme.DARK));
    }

    @Test
    @DisplayName("POST /logout com Origin válida deve revogar autorização do cookie refresh_token e limpar cookies")
    void deveRevogarAutorizacaoNoLogoutComOriginValida() throws Exception {
        OAuth2Authorization authorization = mock(OAuth2Authorization.class);
        when(authorizationService.findByToken("rt-ativo-123", OAuth2TokenType.REFRESH_TOKEN))
                .thenReturn(authorization);

        mockMvc.perform(post("/logout")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .cookie(new Cookie("refresh_token", "rt-ativo-123")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));

        verify(authorizationService).findByToken("rt-ativo-123", OAuth2TokenType.REFRESH_TOKEN);
        verify(authorizationService).remove(authorization);
    }

    @Test
    @DisplayName("POST /logout sem Origin/Referer ou com Origin maliciosa deve retornar 403 Forbidden sem revogar autorização")
    void deveRejeitarLogoutComOriginInvalidaOuAusente() throws Exception {
        mockMvc.perform(post("/logout")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .cookie(new Cookie("refresh_token", "rt-ativo-123")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/logout")
                        .cookie(new Cookie("refresh_token", "rt-ativo-123")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(authorizationService);
    }

    private static String createValidToken(String subject, Long userId) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer("http://localhost:8081")
                .claim("user_id", userId)
                .claim("roles", Set.of("ROLE_USER"))
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + Duration.ofHours(1).toMillis()))
                .build();

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("auth-key-id").build(),
                claims
        );
        signedJWT.sign(new RSASSASigner((RSAPrivateKey) KEY_PAIR.getPrivate()));
        return signedJWT.serialize();
    }
}
