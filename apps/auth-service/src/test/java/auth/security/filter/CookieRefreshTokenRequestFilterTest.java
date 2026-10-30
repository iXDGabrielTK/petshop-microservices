package auth.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CookieRefreshTokenRequestFilter — Testes Unitários de Proteção CSRF e Extração de Cookie")
class CookieRefreshTokenRequestFilterTest {

    @InjectMocks
    private CookieRefreshTokenRequestFilter filter;

    private static final String FRONTEND_URL = "http://localhost:5173";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(filter, "frontendUrl", FRONTEND_URL);
    }

    @Test
    @DisplayName("Deve repassar requisições para outras URIs sem validação ou modificação")
    void deveIgnorarOutrasUris() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/usuarios/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("Deve repassar requisição /oauth2/token com grant_type diferente de refresh_token sem bloqueio CSRF")
    void deveIgnorarTokenEndpointComOutroGrantType() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
        request.setParameter("grant_type", "authorization_code");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Nested
    @DisplayName("Validação CSRF de Origin e Referer")
    class CsrfValidationTests {

        @Test
        @DisplayName("Deve bloquear com 403 Forbidden quando Origin e Referer forem nulos")
        void deveBloquearQuandoOriginERefererForemNulos() throws ServletException, IOException {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
            request.setParameter("grant_type", "refresh_token");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
            assertThat(response.getErrorMessage()).isEqualTo("CSRF: Invalid Origin/Referer");
            verify(chain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("Deve bloquear com 403 quando Origin e Referer forem de domínios maliciosos")
        void deveBloquearQuandoOriginMalicioso() throws ServletException, IOException {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
            request.setParameter("grant_type", "refresh_token");
            request.addHeader("Origin", "http://evil-attacker.com");
            request.addHeader("Referer", "http://evil-attacker.com/login");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
            verify(chain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("Deve aceitar quando Origin for exatamente frontendUrl com barra no final")
        void deveAceitarQuandoOriginComBarraFinal() throws ServletException, IOException {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
            request.setParameter("grant_type", "refresh_token");
            request.addHeader("Origin", FRONTEND_URL + "/");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
        }

        @Test
        @DisplayName("Deve aceitar quando Origin for nulo mas Referer começar com frontendUrl")
        void deveAceitarQuandoRefererValido() throws ServletException, IOException {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
            request.setParameter("grant_type", "refresh_token");
            request.addHeader("Referer", FRONTEND_URL + "/app/settings");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
        }
    }

    @Nested
    @DisplayName("Extração de Cookie refresh_token e Envelopamento")
    class CookieWrappingTests {

        @Test
        @DisplayName("Deve passar a requisição original se o cookie refresh_token não estiver presente")
        void devePassarRequisicaoOriginalSemCookie() throws ServletException, IOException {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
            request.setParameter("grant_type", "refresh_token");
            request.addHeader("Origin", FRONTEND_URL);
            request.setCookies(new Cookie("OTHER_COOKIE", "valor"));
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
        }

        @Test
        @DisplayName("Deve envelopar a requisição e injetar refresh_token dos cookies nos parâmetros")
        void deveEnveloparRequisicaoComRefreshTokenDoCookie() throws ServletException, IOException {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token");
            request.setParameter("grant_type", "refresh_token");
            request.setParameter("client_id", "petshop-client");
            request.addHeader("Origin", FRONTEND_URL);
            request.setCookies(new Cookie("refresh_token", "cookie-token-value-xyz"));

            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            ArgumentCaptor<ServletRequest> captor = ArgumentCaptor.forClass(ServletRequest.class);
            verify(chain).doFilter(captor.capture(), eq(response));

            ServletRequest wrappedRequest = captor.getValue();
            assertThat(wrappedRequest).isNotSameAs(request);

            // Validações do Wrapper
            assertThat(wrappedRequest.getParameter("refresh_token")).isEqualTo("cookie-token-value-xyz");
            assertThat(wrappedRequest.getParameter("client_id")).isEqualTo("petshop-client");
            assertThat(wrappedRequest.getParameter("inexistente")).isNull();

            assertThat(wrappedRequest.getParameterValues("refresh_token")).containsExactly("cookie-token-value-xyz");
            assertThat(wrappedRequest.getParameterValues("client_id")).containsExactly("petshop-client");

            Map<String, String[]> paramMap = wrappedRequest.getParameterMap();
            assertThat(paramMap).containsKey("refresh_token");
            assertThat(paramMap.get("refresh_token")).containsExactly("cookie-token-value-xyz");
            assertThat(paramMap.get("client_id")).containsExactly("petshop-client");

            assertThat(Collections.list(wrappedRequest.getParameterNames()))
                    .contains("refresh_token", "grant_type", "client_id");
        }
    }
}
