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
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
    @DisplayName("Deve ignorar requisições que não terminam com /oauth2/token")
    void deveIgnorarUrisDiferentes() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/usuarios/me");
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
    @DisplayName("Deve extrair refresh_token do JSON para Cookie HttpOnly e remover do body quando status for 200")
    void deveExtrairRefreshTokenParaCookieERemoverDoBody() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
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

        // Valida Cookie seguro gerado
        String setCookieHeader = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookieHeader).isNotNull();
        assertThat(setCookieHeader).contains("refresh_token=secret-refresh-token-456");
        assertThat(setCookieHeader).contains("Path=/oauth2/token");
        assertThat(setCookieHeader).contains("HttpOnly");
        assertThat(setCookieHeader).contains("Secure");
        assertThat(setCookieHeader).contains("SameSite=Lax");
        assertThat(setCookieHeader).contains("Max-Age=86400");

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
    @DisplayName("Não deve extrair cookie se o JSON não contiver refresh_token")
    void naoDeveCriarCookieSeJsonNaoContiverRefreshToken() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
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
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
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

    @Test
    @DisplayName("Em caso de erro de parse de JSON (corpo não-JSON), deve manter o body original por segurança de falha")
    void deveManterBodyOriginalSeFalharParseJson() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String invalidJson = "Not a json response";

        FilterChain filterChain = (req, res) -> {
            ContentCachingResponseWrapper wrapper = (ContentCachingResponseWrapper) res;
            wrapper.setStatus(200);
            wrapper.getOutputStream().write(invalidJson.getBytes(StandardCharsets.UTF_8));
        };

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo("Not a json response");
    }
}
