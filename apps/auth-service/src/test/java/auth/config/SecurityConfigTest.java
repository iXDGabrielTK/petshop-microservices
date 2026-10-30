package auth.config;

import auth.security.filter.CookieRefreshTokenRequestFilter;
import auth.security.filter.RefreshTokenCookieFilter;
import auth.security.handler.CookieClearingLogoutHandler;
import auth.security.user.UserDetailsImpl;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import common.security.RsaKeyUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.security.KeyPair;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SecurityConfig — Testes Unitários de Beans de Configuração de Segurança")
class SecurityConfigTest {

    @Mock
    private CookieRefreshTokenRequestFilter cookieRefreshTokenRequestFilter;

    @Mock
    private RefreshTokenCookieFilter refreshTokenCookieFilter;

    @Mock
    private CookieClearingLogoutHandler cookieClearingLogoutHandler;

    private SecurityConfig securityConfig;

    @BeforeEach
    void setUp() {
        securityConfig = new SecurityConfig(
                cookieRefreshTokenRequestFilter,
                refreshTokenCookieFilter,
                cookieClearingLogoutHandler
        );
        ReflectionTestUtils.setField(securityConfig, "frontendBaseUrl", "http://localhost:5173");
        ReflectionTestUtils.setField(securityConfig, "corsAllowedMethods", "GET,POST,PUT,DELETE,OPTIONS");
        ReflectionTestUtils.setField(securityConfig, "corsAllowedHeaders", "Authorization,Content-Type");
        ReflectionTestUtils.setField(securityConfig, "corsAllowCredentials", true);
    }

    @Nested
    @DisplayName("corsConfigurationSource()")
    class CorsTests {

        @Test
        @DisplayName("Deve configurar CORS corretamente a partir das propriedades")
        void deveConfigurarCorsCorretamente() {
            CorsConfigurationSource source = securityConfig.corsConfigurationSource();
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/usuarios/me");

            CorsConfiguration config = source.getCorsConfiguration(request);
            assertThat(config).isNotNull();
            assertThat(config.getAllowedOrigins()).containsExactly("http://localhost:5173");
            assertThat(config.getAllowedMethods()).containsExactly("GET", "POST", "PUT", "DELETE", "OPTIONS");
            assertThat(config.getAllowedHeaders()).containsExactly("Authorization", "Content-Type");
            assertThat(config.getAllowCredentials()).isTrue();
        }
    }

    @Nested
    @DisplayName("passwordEncoder()")
    class PasswordEncoderTests {

        @Test
        @DisplayName("Deve fornecer BCryptPasswordEncoder funcional")
        void deveFornecerBCryptPasswordEncoder() {
            PasswordEncoder encoder = securityConfig.passwordEncoder();
            assertThat(encoder).isNotNull();

            String raw = "MinhaSenhaForte@123";
            String encoded = encoder.encode(raw);

            assertThat(encoded).isNotEqualTo(raw);
            assertThat(encoder.matches(raw, encoded)).isTrue();
            assertThat(encoder.matches("OutraSenha", encoded)).isFalse();
        }
    }

    @Nested
    @DisplayName("jwtTokenCustomizer()")
    class JwtTokenCustomizerTests {

        @Test
        @DisplayName("Deve customizar claims do token JWT quando tokenType for ACCESS_TOKEN e principal for UserDetailsImpl")
        void deveCustomizarClaimsParaAccessTokenComUserDetailsImpl() {
            OAuth2TokenCustomizer<JwtEncodingContext> customizer = securityConfig.jwtTokenCustomizer();

            JwtEncodingContext context = mock(JwtEncodingContext.class);
            when(context.getTokenType()).thenReturn(OAuth2TokenType.ACCESS_TOKEN);

            UserDetailsImpl userDetails = new UserDetailsImpl(
                    123L,
                    "Gabriel Admin",
                    "admin@petshop.com",
                    "hash",
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"))
            );

            Authentication auth = new UsernamePasswordAuthenticationToken(
                    userDetails,
                    null,
                    userDetails.getAuthorities()
            );
            when(context.getPrincipal()).thenReturn(auth);

            JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder();
            when(context.getClaims()).thenReturn(claimsBuilder);

            // Execute
            customizer.customize(context);

            JwtClaimsSet claims = claimsBuilder.build();
            assertThat((Iterable<String>) claims.getClaims().get("roles"))
                    .contains("ROLE_ADMIN", "ROLE_USER");
            assertThat(claims.getClaims().get("name")).isEqualTo("Gabriel Admin");
            assertThat(claims.getClaims().get("user_id")).isEqualTo(123L);
            assertThat(claims.getClaims().get("email")).isEqualTo("admin@petshop.com");
        }

        @Test
        @DisplayName("Não deve customizar claims se tokenType não for ACCESS_TOKEN")
        void naoDeveCustomizarSeNaoForAccessToken() {
            OAuth2TokenCustomizer<JwtEncodingContext> customizer = securityConfig.jwtTokenCustomizer();

            JwtEncodingContext context = mock(JwtEncodingContext.class);
            when(context.getTokenType()).thenReturn(new OAuth2TokenType("refresh_token"));

            customizer.customize(context);

            verify(context, never()).getClaims();
        }
    }

    @Nested
    @DisplayName("customAuthenticationSuccessHandler()")
    class SuccessHandlerTests {

        @Test
        @DisplayName("Deve redirecionar para frontendBaseUrl quando não houver SavedRequest no cache")
        void deveRedirecionarParaFrontendBaseUrlSemSavedRequest() throws Exception {
            AuthenticationSuccessHandler handler = securityConfig.customAuthenticationSuccessHandler();

            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            Authentication auth = mock(Authentication.class);

            handler.onAuthenticationSuccess(request, response, auth);

            assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:5173");
        }

        @Test
        @DisplayName("Deve redirecionar para targetUrl quando houver SavedRequest no cache")
        void deveRedirecionarParaTargetUrlComSavedRequest() throws Exception {
            AuthenticationSuccessHandler handler = securityConfig.customAuthenticationSuccessHandler();

            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorize");
            request.setQueryString("client_id=petshop-client");
            MockHttpSession session = new MockHttpSession();
            request.setSession(session);

            MockHttpServletResponse response = new MockHttpServletResponse();
            Authentication auth = mock(Authentication.class);

            // Armazena uma SavedRequest na sessão
            HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
            requestCache.saveRequest(request, response);

            // Simula o login POST que sucede
            MockHttpServletRequest loginRequest = new MockHttpServletRequest("POST", "/login");
            loginRequest.setSession(session);
            MockHttpServletResponse loginResponse = new MockHttpServletResponse();

            handler.onAuthenticationSuccess(loginRequest, loginResponse, auth);

            assertThat(loginResponse.getRedirectedUrl()).contains("/oauth2/authorize?client_id=petshop-client");
        }
    }

    @Nested
    @DisplayName("jwkSource()")
    class JwkSourceTests {

        @Test
        @DisplayName("Deve gerar chaves em memória quando chaves não estiverem configuradas nas properties")
        void deveGerarChavesEmMemoria() {
            JWKSource<SecurityContext> jwkSource = securityConfig.jwkSource();
            assertThat(jwkSource).isNotNull();
        }

        @Test
        @DisplayName("Deve fazer parse das chaves RSA quando configuradas nas properties")
        void deveUsarChavesConfiguradas() {
            KeyPair keyPair = RsaKeyUtils.generateKeyPair();
            String pubKeyPem = java.util.Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
            String privKeyPem = java.util.Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());

            ReflectionTestUtils.setField(securityConfig, "publicKeyString", pubKeyPem);
            ReflectionTestUtils.setField(securityConfig, "privateKeyString", privKeyPem);

            JWKSource<SecurityContext> jwkSource = securityConfig.jwkSource();
            assertThat(jwkSource).isNotNull();
        }
    }

    @Nested
    @DisplayName("OAuth2 Server Beans")
    class OAuth2ServerBeansTests {

        @Test
        @DisplayName("authorizationServerSettings() deve retornar configurações com endpoints padrão do OAuth2")
        void deveRetornarAuthorizationServerSettings() {
            var settings = securityConfig.authorizationServerSettings();
            assertThat(settings).isNotNull();
            assertThat(settings.getTokenEndpoint()).isEqualTo("/oauth2/token");
            assertThat(settings.getAuthorizationEndpoint()).isEqualTo("/oauth2/authorize");
            assertThat(settings.getJwkSetEndpoint()).isEqualTo("/oauth2/jwks");
        }
    }
}
