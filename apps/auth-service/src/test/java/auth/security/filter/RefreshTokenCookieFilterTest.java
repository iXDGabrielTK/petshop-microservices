/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package auth.security.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("RefreshTokenCookieFilter — Testes Unitários de Extração de Refresh Token para Cookie HttpOnly")
class RefreshTokenCookieFilterTest {

    @InjectMocks
    private RefreshTokenCookieFilter filter;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(filter, "isCookieSecure", true);
        ReflectionTestUtils.setField(filter, "cookieSameSite", "Lax");
    }

    @Test
    void habilitaSecurePorPadraoEPermiteHttpNoAmbienteLocal() throws IOException {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addLast(new ResourcePropertySource("classpath:application.properties"));
        assertThat(environment.getRequiredProperty("auth.cookie.secure", Boolean.class)).isTrue();
        environment.setProperty("AUTH_COOKIE_SECURE", "false");
        assertThat(environment.getRequiredProperty("auth.cookie.secure", Boolean.class)).isFalse();
    }

    @Test
    @DisplayName("Deve ignorar requisições que não correspondam a POST /oauth2/token")
    void deveIgnorarUrisDiferentes() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/usuarios/me");
        request.setServletPath("/usuarios/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain filterChain = (req, res) -> {
            res.getWriter().write("OK");
        };

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isNull();
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(response.getContentAsString()).isEqualTo("OK");
    }

    @Test
    @DisplayName("Deve ignorar GET /oauth2/token e POST /oauth2/tokenx")
    void deveIgnorarMetodoGetESufixoTokenx() throws ServletException, IOException {
        for (MockHttpServletRequest request : new MockHttpServletRequest[]{
                requestComPath("GET", "/oauth2/token", "/oauth2/token"),
                requestComPath("POST", "/oauth2/tokenx", "/oauth2/tokenx")
        }) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain filterChain = (req, res) -> res.getWriter().write("OK");

            filter.doFilterInternal(request, response, filterChain);

            assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isNull();
            assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
            assertThat(response.getContentAsString()).isEqualTo("OK");
        }
    }

    @Test
    @DisplayName("Deve atuar normalmente quando /oauth2/token chegar com percent-encoding (/oauth2/%74oken)")
    void deveAtuarEmPathComPercentEncoding() throws ServletException, IOException {
        MockHttpServletRequest request = requestComPath("POST", "/oauth2/%74oken", "/oauth2/token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String originalJsonResponse = """
                {
                    "access_token": "jwt-access-token-encoded",
                    "refresh_token": "secret-refresh-token-encoded",
                    "token_type": "Bearer",
                    "expires_in": 1800
                }
                """;

        FilterChain filterChain = (req, res) -> {
            ContentCachingResponseWrapper wrapper = (ContentCachingResponseWrapper) res;
            wrapper.setStatus(200);
            wrapper.getOutputStream().write(originalJsonResponse.getBytes(StandardCharsets.UTF_8));
        };

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("refresh_token=secret-refresh-token-encoded");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode jsonNode = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(jsonNode.has("refresh_token")).isFalse();
    }

    @Test
    @DisplayName("Deve extrair refresh_token do JSON para Cookie HttpOnly e remover do body quando status for 200")
    void deveExtrairRefreshTokenParaCookieERemoverDoBody() throws ServletException, IOException {
        MockHttpServletRequest request = tokenRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String originalJsonResponse = """
                {
                    "access_token": "jwt-access-token-123",
                    "refresh_token": "secret-refresh-token-456",
                    "token_type": "Bearer",
                    "expires_in": 1800
                }
                """;

        FilterChain filterChain = (req, res) -> {
            ContentCachingResponseWrapper wrapper = (ContentCachingResponseWrapper) res;
            wrapper.setStatus(200);
            wrapper.getOutputStream().write(originalJsonResponse.getBytes(StandardCharsets.UTF_8));
        };

        filter.doFilterInternal(request, response, filterChain);

        // Valida Headers de Cache
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store, no-cache, must-revalidate, max-age=0");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");

        // Valida Cookie seguro gerado e expiração do cookie legado (/oauth2/token)
        List<String> setCookieHeaders = response.getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(setCookieHeaders).hasSize(2);

        String setCookieHeader = setCookieHeaders.get(0);
        assertThat(setCookieHeader).contains("refresh_token=secret-refresh-token-456");
        assertThat(setCookieHeader).contains("Path=/oauth2;");
        assertThat(setCookieHeader).doesNotContain("Path=/oauth2/token");
        assertThat(setCookieHeader).contains("HttpOnly");
        assertThat(setCookieHeader).contains("Secure");
        assertThat(setCookieHeader).contains("SameSite=Lax");
        assertThat(setCookieHeader).contains("Max-Age=86400");

        String legacyCookieHeader = setCookieHeaders.get(1);
        assertThat(legacyCookieHeader).contains("refresh_token=;");
        assertThat(legacyCookieHeader).contains("Path=/oauth2/token;");
        assertThat(legacyCookieHeader).contains("Max-Age=0");
        assertThat(legacyCookieHeader).contains("HttpOnly");
        assertThat(legacyCookieHeader).contains("Secure");
        assertThat(legacyCookieHeader).contains("SameSite=Lax");

        // Valida que o body foi reescrito sem o refresh_token
        String newResponseBody = response.getContentAsString(StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode jsonNode = mapper.readTree(newResponseBody);

        assertThat(jsonNode.has("refresh_token")).isFalse();
        assertThat(jsonNode.has("access_token")).isTrue();
        assertThat(jsonNode.get("access_token").asText()).isEqualTo("jwt-access-token-123");
        assertThat(jsonNode.get("token_type").asText()).isEqualTo("Bearer");
    }

    @Test
    @DisplayName("Deve espelhar atributos de segurança configurados (secure=false, sameSite=Strict) no cookie atual e no legado")
    void deveEspelharAtributosConfiguradosNosDoisCookies() throws ServletException, IOException {
        ReflectionTestUtils.setField(filter, "isCookieSecure", false);
        ReflectionTestUtils.setField(filter, "cookieSameSite", "Strict");

        MockHttpServletRequest request = tokenRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain filterChain = (req, res) -> {
            ContentCachingResponseWrapper wrapper = (ContentCachingResponseWrapper) res;
            wrapper.setStatus(200);
            wrapper.getOutputStream().write("{\"access_token\":\"at\",\"refresh_token\":\"rt-novo\"}".getBytes(StandardCharsets.UTF_8));
        };

        filter.doFilterInternal(request, response, filterChain);

        List<String> setCookieHeaders = response.getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(setCookieHeaders).hasSize(2);

        String activeCookie = setCookieHeaders.get(0);
        assertThat(activeCookie)
                .contains("refresh_token=rt-novo")
                .contains("Path=/oauth2;")
                .contains("Max-Age=86400")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .doesNotContain("Secure")
                .doesNotContain("Domain=");

        String legacyCookie = setCookieHeaders.get(1);
        assertThat(legacyCookie)
                .contains("refresh_token=;")
                .contains("Path=/oauth2/token;")
                .contains("Max-Age=0")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .doesNotContain("Secure")
                .doesNotContain("Domain=");
    }

    @Test
    @DisplayName("Não deve extrair cookie se o JSON não contiver refresh_token")
    void naoDeveCriarCookieSeJsonNaoContiverRefreshToken() throws ServletException, IOException {
        MockHttpServletRequest request = tokenRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String jsonResponse = """
                {
                    "access_token": "jwt-access-token-only",
                    "token_type": "Bearer"
                }
                """;

        FilterChain filterChain = (req, res) -> {
            ContentCachingResponseWrapper wrapper = (ContentCachingResponseWrapper) res;
            wrapper.setStatus(200);
            wrapper.getOutputStream().write(jsonResponse.getBytes(StandardCharsets.UTF_8));
        };

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("jwt-access-token-only");
    }

    @Test
    @DisplayName("Não deve alterar body nem extrair cookie se status for diferente de 200 (ex: 400 Bad Request)")
    void naoDeveCriarCookieSeStatusDiferenteDe200() throws ServletException, IOException {
        MockHttpServletRequest request = tokenRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String errorResponse = """
                {"error": "invalid_grant", "error_description": "Invalid refresh token"}
                """;

        FilterChain filterChain = (req, res) -> {
            ContentCachingResponseWrapper wrapper = (ContentCachingResponseWrapper) res;
            wrapper.setStatus(400);
            wrapper.getOutputStream().write(errorResponse.getBytes(StandardCharsets.UTF_8));
        };

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store, no-cache, must-revalidate, max-age=0");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("invalid_grant");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"refresh_token\":\"segredo\", ",
            "{\"refresh_token\":\"segredo\"} {}",
            "{\"refresh_token\":\"segredo\",\"refresh_token\":null}",
            "{\"refresh_token\":{\"valor\":\"segredo\"}}"
    })
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("Deve descartar a resposta OAuth2 inválida antes de responder 500")
    void deveDescartarBodySeFalharParseJson(String invalidJson, CapturedOutput output) throws ServletException, IOException {
        MockHttpServletRequest request = tokenRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain filterChain = (req, res) -> {
            ContentCachingResponseWrapper wrapper = (ContentCachingResponseWrapper) res;
            wrapper.setStatus(200);
            wrapper.getOutputStream().write(invalidJson.getBytes(StandardCharsets.UTF_8));
        };

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo("{\"error\":\"server_error\"}");
        assertThat(output.getAll()).contains("Falha ao processar a resposta OAuth2").doesNotContain("segredo");
    }

    private static MockHttpServletRequest tokenRequest() {
        return requestComPath("POST", "/oauth2/token", "/oauth2/token");
    }

    private static MockHttpServletRequest requestComPath(String method, String requestUri, String servletPath) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, requestUri);
        request.setServletPath(servletPath);
        return request;
    }
}
