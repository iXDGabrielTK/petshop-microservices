/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package auth.config;

import auth.controller.AuthController;
import auth.controller.UsuarioController;
import auth.model.PasswordResetToken;
import auth.model.Role;
import auth.model.Usuario;
import auth.repository.PasswordResetTokenRepository;
import auth.repository.RoleRepository;
import auth.repository.UsuarioRepository;
import auth.security.ClientIpResolver;
import auth.security.LoginAttemptService;
import auth.security.filter.CookieRefreshTokenRequestFilter;
import auth.security.filter.RefreshTokenCookieFilter;
import auth.security.handler.CookieClearingLogoutHandler;
import auth.security.user.UserDetailsImpl;
import auth.service.AuthService;
import auth.service.UsuarioService;
import common.exception.GlobalExceptionHandler;
import common.security.RsaKeyUtils;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(classes = AuthTokenRevocationTest.TestContextConfig.class)
@WebAppConfiguration
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AuthTokenRevocationTest — Revogação real de refresh_token no JdbcOAuth2AuthorizationService (H2)")
class AuthTokenRevocationTest {

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
            AuthService.class,
            AuthController.class,
            UsuarioController.class,
            GlobalExceptionHandler.class
    })
    static class TestContextConfig {

        @Bean
        DataSource dataSource() {
            DriverManagerDataSource ds = new DriverManagerDataSource(
                    "jdbc:h2:mem:auth_revocation_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                    "sa",
                    ""
            );
            ds.setDriverClassName("org.h2.Driver");
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                    new ClassPathResource("db/migration/V2__create_oauth_tables.sql")
            );
            populator.execute(ds);
            return ds;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }

    private static final KeyPair KEY_PAIR = RsaKeyUtils.generateKeyPair();
    private static final String CLIENT_UUID = "11111111-1111-1111-1111-111111111111";
    private static final String CLIENT_ID = "petshop-client";
    private static final String CLIENT_SECRET = "secret";
    private static final String BASIC_AUTH = "Basic " + Base64.getEncoder()
            .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
    private static final String ORIGIN = "http://localhost:5173";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String pubKey = Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded());
        String privKey = Base64.getEncoder().encodeToString(KEY_PAIR.getPrivate().getEncoded());
        registry.add("jwt.public.key", () -> pubKey);
        registry.add("jwt.private.key", () -> privKey);
        registry.add("frontend.base-url", () -> ORIGIN);
        registry.add("cors.allowed-methods", () -> "GET,POST,PATCH,PUT,DELETE,OPTIONS");
        registry.add("cors.allowed-headers", () -> "Authorization,Content-Type");
        registry.add("cors.allow-credentials", () -> "true");
        registry.add("auth.cookie.secure", () -> "true");
        registry.add("auth.cookie.same-site", () -> "Lax");
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private OAuth2AuthorizationService authorizationService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private UsuarioRepository usuarioRepository;

    @MockitoBean
    private PasswordResetTokenRepository tokenRepository;

    @MockitoBean
    private RoleRepository roleRepository;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private UsuarioService usuarioService;

    private MockMvc mockMvc;
    private RegisteredClient registeredClient;

    @BeforeAll
    void setUpOnce() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        registeredClient = RegisteredClient.withId(CLIENT_UUID)
                .clientId(CLIENT_ID)
                .clientSecret(passwordEncoder.encode(CLIENT_SECRET))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(ORIGIN + "/authorized")
                .scope("pets:read")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(30))
                        .refreshTokenTimeToLive(Duration.ofDays(1))
                        .reuseRefreshTokens(false)
                        .build())
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .build())
                .build();
        registeredClientRepository.save(registeredClient);
    }

    @BeforeEach
    void limparAutorizacoes() {
        jdbcTemplate.update("DELETE FROM oauth2_authorization");
    }

    @Test
    @DisplayName("Reuso de refresh_token após POST /logout deve falhar com invalid_grant")
    void deveRejeitarReusoDeRefreshTokenAposLogout() throws Exception {
        salvarAutorizacaoAtiva("user@petshop.com", 10L, "at-logout-1", "rt-logout-1");

        mockMvc.perform(post("/logout")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(new Cookie("refresh_token", "rt-logout-1")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));

        assertThat(authorizationService.findByToken("rt-logout-1", OAuth2TokenType.REFRESH_TOKEN)).isNull();

        mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH)
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .cookie(new Cookie("refresh_token", "rt-logout-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    @DisplayName("POST /usuarios/reset-password deve revogar múltiplas sessões do usuário e preservar sessões de outros usuários")
    void deveRevogarMultiplasSessoesNoResetPasswordEPreservarOutrosUsuarios() throws Exception {
        salvarAutorizacaoAtiva("user@petshop.com", 10L, "at-sessao-1", "rt-sessao-1");
        salvarAutorizacaoAtiva("user@petshop.com", 10L, "at-sessao-2", "rt-sessao-2");
        salvarAutorizacaoAtiva("outro@petshop.com", 20L, "at-outro-1", "rt-outro-usuario");

        Usuario usuario = new Usuario("Usuario Teste", "user@petshop.com", "hash-antigo");
        usuario.adicionarRole(new Role(1L, "USER"));
        ReflectionTestUtils.setField(usuario, "id", 10L);
        PasswordResetToken resetToken = new PasswordResetToken("reset-token-valido", usuario);

        when(tokenRepository.findByToken("reset-token-valido")).thenReturn(Optional.of(resetToken));
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/usuarios/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "token": "reset-token-valido",
                                  "newPassword": "NovaSenha@123",
                                  "confirmPassword": "NovaSenha@123"
                                }
                                """))
                .andExpect(status().isOk());

        assertThat(authorizationService.findByToken("rt-sessao-1", OAuth2TokenType.REFRESH_TOKEN)).isNull();
        assertThat(authorizationService.findByToken("rt-sessao-2", OAuth2TokenType.REFRESH_TOKEN)).isNull();
        assertThat(authorizationService.findByToken("rt-outro-usuario", OAuth2TokenType.REFRESH_TOKEN)).isNotNull();

        for (String rtRevogado : List.of("rt-sessao-1", "rt-sessao-2")) {
            mockMvc.perform(post("/oauth2/token")
                            .header(HttpHeaders.ORIGIN, ORIGIN)
                            .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH)
                            .accept(MediaType.APPLICATION_JSON)
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("grant_type", "refresh_token")
                            .cookie(new Cookie("refresh_token", rtRevogado)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_grant"));
        }

        mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH)
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .cookie(new Cookie("refresh_token", "rt-outro-usuario")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty());
    }

    @Test
    @DisplayName("POST /oauth2/%74oken deve validar CSRF e aplicar extração/rotação do cookie refresh_token")
    void deveProcessarTokenEndpointComPercentEncoding() throws Exception {
        salvarAutorizacaoAtiva("user@petshop.com", 10L, "at-enc-1", "rt-enc-1");

        mockMvc.perform(post(URI.create("/oauth2/%74oken"))
                        .header(HttpHeaders.ORIGIN, "http://evil.com")
                        .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH)
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .cookie(new Cookie("refresh_token", "rt-enc-1")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(URI.create("/oauth2/%74oken"))
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH)
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .cookie(new Cookie("refresh_token", "rt-enc-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refresh_token").doesNotExist())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("refresh_token=")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Path=/oauth2;")));
    }

    @Test
    @DisplayName("POST /oauth2/logout apenas com cookie refresh_token e Origin válido deve revogar sessão e limpar cookies /oauth2 e legado")
    void deveRevogarSessaoEmOAuth2LogoutApenasComCookie() throws Exception {
        salvarAutorizacaoAtiva("user@petshop.com", 10L, "at-oauth2-logout", "rt-oauth2-logout");

        mockMvc.perform(post("/oauth2/logout")
                        .cookie(new Cookie("refresh_token", "rt-oauth2-logout")))
                .andExpect(status().isForbidden());

        assertThat(authorizationService.findByToken("rt-oauth2-logout", OAuth2TokenType.REFRESH_TOKEN)).isNotNull();

        mockMvc.perform(post("/oauth2/logout")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(new Cookie("refresh_token", "rt-oauth2-logout")))
                .andExpect(status().isOk())
                .andExpect(header().stringValues(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("Path=/oauth2;"))))
                .andExpect(header().stringValues(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("Path=/oauth2/token;"))));

        assertThat(authorizationService.findByToken("rt-oauth2-logout", OAuth2TokenType.REFRESH_TOKEN)).isNull();

        mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH)
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .cookie(new Cookie("refresh_token", "rt-oauth2-logout")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    @DisplayName("POST /oauth2/%6cogout deve validar Origin e revogar o refresh_token normalmente")
    void deveValidarOriginERevogarEmOAuth2LogoutComPercentEncoding() throws Exception {
        salvarAutorizacaoAtiva("user@petshop.com", 10L, "at-oauth2-enc-logout", "rt-oauth2-enc-logout");

        mockMvc.perform(post(URI.create("/oauth2/%6cogout"))
                        .header(HttpHeaders.ORIGIN, "http://evil.com")
                        .cookie(new Cookie("refresh_token", "rt-oauth2-enc-logout")))
                .andExpect(status().isForbidden());

        assertThat(authorizationService.findByToken("rt-oauth2-enc-logout", OAuth2TokenType.REFRESH_TOKEN)).isNotNull();

        mockMvc.perform(post(URI.create("/oauth2/%6cogout"))
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(new Cookie("refresh_token", "rt-oauth2-enc-logout")))
                .andExpect(status().isOk());

        assertThat(authorizationService.findByToken("rt-oauth2-enc-logout", OAuth2TokenType.REFRESH_TOKEN)).isNull();
    }

    @Test
    @DisplayName("POST /%6cogout deve validar Origin e revogar o refresh_token normalmente")
    void deveValidarOriginERevogarNoLogoutComPercentEncoding() throws Exception {
        salvarAutorizacaoAtiva("user@petshop.com", 10L, "at-enc-logout", "rt-enc-logout");

        mockMvc.perform(post(URI.create("/%6cogout"))
                        .header(HttpHeaders.ORIGIN, "http://evil.com")
                        .cookie(new Cookie("refresh_token", "rt-enc-logout")))
                .andExpect(status().isForbidden());

        assertThat(authorizationService.findByToken("rt-enc-logout", OAuth2TokenType.REFRESH_TOKEN)).isNotNull();

        mockMvc.perform(post(URI.create("/%6cogout"))
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(new Cookie("refresh_token", "rt-enc-logout")))
                .andExpect(status().isOk());

        assertThat(authorizationService.findByToken("rt-enc-logout", OAuth2TokenType.REFRESH_TOKEN)).isNull();
    }

    @Test
    @DisplayName("Duas renovações consecutivas via /oauth2/token devem rotacionar refresh_token em /oauth2 e expirar cookie legado /oauth2/token")
    void devePermitirDuasRenovacoesConsecutivasEExpirarCookieLegado() throws Exception {
        salvarAutorizacaoAtiva("user@petshop.com", 10L, "at-rot-1", "rt-rot-1");

        Cookie legacyCookie = new Cookie("refresh_token", "rt-rot-1");
        legacyCookie.setPath("/oauth2/token");

        MvcResult result1 = mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH)
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .cookie(legacyCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").doesNotExist())
                .andReturn();

        List<String> cookies1 = result1.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(cookies1).hasSize(2);
        String activeCookie1 = cookies1.stream()
                .filter(c -> c.contains("Path=/oauth2;"))
                .findFirst()
                .orElseThrow();
        String legacyCookie1 = cookies1.stream()
                .filter(c -> c.contains("Path=/oauth2/token;"))
                .findFirst()
                .orElseThrow();
        assertThat(activeCookie1).contains("Max-Age=86400").contains("HttpOnly").contains("Secure").contains("SameSite=Lax");
        assertThat(legacyCookie1).contains("refresh_token=;").contains("Max-Age=0").contains("HttpOnly").contains("Secure").contains("SameSite=Lax");

        String rt2 = extrairValorCookie(activeCookie1);
        assertThat(rt2).isNotBlank().isNotEqualTo("rt-rot-1");

        mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH)
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .cookie(new Cookie("refresh_token", "rt-rot-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));

        MvcResult result2 = mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH)
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .cookie(new Cookie("refresh_token", rt2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").doesNotExist())
                .andReturn();

        List<String> cookies2 = result2.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(cookies2).hasSize(2);
        String activeCookie2 = cookies2.stream()
                .filter(c -> c.contains("Path=/oauth2;"))
                .findFirst()
                .orElseThrow();
        String legacyCookie2 = cookies2.stream()
                .filter(c -> c.contains("Path=/oauth2/token;"))
                .findFirst()
                .orElseThrow();
        assertThat(activeCookie2).contains("Max-Age=86400").contains("HttpOnly").contains("Secure").contains("SameSite=Lax");
        assertThat(legacyCookie2).contains("refresh_token=;").contains("Max-Age=0").contains("HttpOnly").contains("Secure").contains("SameSite=Lax");

        String rt3 = extrairValorCookie(activeCookie2);
        assertThat(rt3).isNotBlank().isNotEqualTo(rt2);

        mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH)
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .cookie(new Cookie("refresh_token", rt2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    @DisplayName("Rotas arbitrárias sob /oauth2/** na cadeia padrão devem exigir CSRF enquanto /oauth2/token, /oauth2/logout e /logout seguem funcionando")
    void deveExigirCsrfParaRotasArbitrariasSobOAuth2NaCadeiaPadraoEManterTokenELogoutFuncionando() throws Exception {
        MockHttpSession session = new MockHttpSession();
        UserDetailsImpl userDetails = new UserDetailsImpl(
                10L, "Usuario", "user@petshop.com", "hash", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(auth));

        mockMvc.perform(get("/oauth2/rota-arbitraria").session(session))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/oauth2/rota-arbitraria").session(session))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/oauth2/rota-arbitraria").session(session).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/oauth2/rota-arbitraria").session(session).with(csrf()))
                .andExpect(status().isNotFound());

        salvarAutorizacaoAtiva("user@petshop.com", 10L, "at-csrf-1", "rt-csrf-1");
        MvcResult tokenResult = mockMvc.perform(post("/oauth2/token")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH)
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .cookie(new Cookie("refresh_token", "rt-csrf-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andReturn();

        String rtAtual = tokenResult.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(c -> c.contains("Path=/oauth2;"))
                .findFirst()
                .map(AuthTokenRevocationTest::extrairValorCookie)
                .orElseThrow();

        mockMvc.perform(post("/oauth2/logout")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(new Cookie("refresh_token", rtAtual)))
                .andExpect(status().isOk());

        salvarAutorizacaoAtiva("user@petshop.com", 10L, "at-csrf-2", "rt-csrf-2");
        mockMvc.perform(post("/logout")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(new Cookie("refresh_token", "rt-csrf-2")))
                .andExpect(status().isOk());
    }

    private static String extrairValorCookie(String setCookieHeader) {
        String prefix = "refresh_token=";
        int start = setCookieHeader.indexOf(prefix) + prefix.length();
        int end = setCookieHeader.indexOf(';', start);
        return end >= 0 ? setCookieHeader.substring(start, end) : setCookieHeader.substring(start);
    }

    private void salvarAutorizacaoAtiva(String email, Long userId, String accessTokenValue, String refreshTokenValue) {
        Instant agora = Instant.now();
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        UserDetailsImpl userDetails = new UserDetailsImpl(userId, "Usuario", email, "hash", authorities);
        UsernamePasswordAuthenticationToken principal = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());

        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                accessTokenValue,
                agora,
                agora.plus(Duration.ofMinutes(30)),
                Set.of("pets:read"));
        OAuth2RefreshToken refreshToken = new OAuth2RefreshToken(
                refreshTokenValue,
                agora,
                agora.plus(Duration.ofDays(1)));

        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(registeredClient)
                .id(UUID.randomUUID().toString())
                .principalName(email)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("pets:read"))
                .attribute(Principal.class.getName(), principal)
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();

        authorizationService.save(authorization);
    }
}
