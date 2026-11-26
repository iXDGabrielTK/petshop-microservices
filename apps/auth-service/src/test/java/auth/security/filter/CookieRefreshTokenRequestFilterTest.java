/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package auth.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
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
        MockHttpServletRequest request = tokenRequest();
        request.setParameter("grant_type", "authorization_code");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("Deve ignorar GET /oauth2/token e POST /oauth2/tokenx")
    void deveIgnorarMetodoGetESufixoTokenx() throws ServletException, IOException {
        for (MockHttpServletRequest request : new MockHttpServletRequest[]{
                requestComPath("GET", "/oauth2/token", "/oauth2/token"),
                requestComPath("POST", "/oauth2/tokenx", "/oauth2/tokenx")
        }) {
            request.setParameter("grant_type", "refresh_token");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
        }
    }

    @Test
    @DisplayName("Deve atuar normalmente quando /oauth2/token chegar com percent-encoding (/oauth2/%74oken)")
    void deveAtuarEmPathComPercentEncoding() throws ServletException, IOException {
        MockHttpServletRequest bloqueado = requestComPath("POST", "/oauth2/%74oken", "/oauth2/token");
        bloqueado.setParameter("grant_type", "refresh_token");
        MockHttpServletResponse responseBloqueado = new MockHttpServletResponse();
        FilterChain chainBloqueado = mock(FilterChain.class);

        filter.doFilter(bloqueado, responseBloqueado, chainBloqueado);

        assertThat(responseBloqueado.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        verifyNoInteractions(chainBloqueado);

        MockHttpServletRequest valido = requestComPath("POST", "/oauth2/%74oken", "/oauth2/token");
        valido.setParameter("grant_type", "refresh_token");
        valido.addHeader("Origin", FRONTEND_URL);
        valido.setCookies(new Cookie("refresh_token", "rt-encoded-123"));
        MockHttpServletResponse responseValido = new MockHttpServletResponse();
        FilterChain chainValido = mock(FilterChain.class);

        filter.doFilter(valido, responseValido, chainValido);

        ArgumentCaptor<ServletRequest> captor = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chainValido).doFilter(captor.capture(), eq(responseValido));
        assertThat(captor.getValue().getParameter("refresh_token")).isEqualTo("rt-encoded-123");
    }

    @Nested
    @DisplayName("Validação CSRF de Origin e Referer")
    class CsrfValidationTests {

        @Test
        @DisplayName("Deve bloquear com 403 Forbidden quando Origin e Referer forem nulos")
        void deveBloquearQuandoOriginERefererForemNulos() throws ServletException, IOException {
            MockHttpServletRequest request = tokenRequest();
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
            MockHttpServletRequest request = tokenRequest();
            request.setParameter("grant_type", "refresh_token");
            request.addHeader("Origin", "http://evil-attacker.com");
            request.addHeader("Referer", "http://evil-attacker.com/login");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
            verify(chain, never()).doFilter(any(), any());
        }

        @ParameterizedTest
        @ValueSource(strings = {"null", "", "https://[", "https://usuario@shop.example",
                "https://shop.example:", "https://shop.example:abc", "/app"})
        void rejeitaCabecalhoInvalidoAntesDeEncaminhar(String invalido) throws Exception {
            ReflectionTestUtils.setField(filter, "frontendUrl", "https://shop.example");
            for (String header : new String[]{"Origin", "Referer"}) {
                MockHttpServletRequest request = tokenRequest();
                request.setParameter("grant_type", "refresh_token");
                request.addHeader(header, invalido);
                if ("Origin".equals(header)) request.addHeader("Referer", "https://shop.example/app");
                MockHttpServletResponse response = new MockHttpServletResponse();
                FilterChain chain = mock(FilterChain.class);

                filter.doFilter(request, response, chain);

                assertThat(response.getStatus()).isEqualTo(403);
                verifyNoInteractions(chain);
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"Origin", "Referer"})
        void rejeitaCabecalhoAmbiguo(String header) throws Exception {
            MockHttpServletRequest request = tokenRequest();
            request.setParameter("grant_type", "refresh_token");
            request.addHeader(header, FRONTEND_URL);
            request.addHeader(header, "https://outro.example");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(403);
            verifyNoInteractions(chain);
        }

        @ParameterizedTest
        @CsvSource({
                "https://shop.example.test/app,403", "http://shop.example/app,403",
                "https://shop.example:8443/app,403", "https://SHOP.EXAMPLE:443/app,200"
        })
        void comparaOrigemDoReferer(String referer, int esperado) throws Exception {
            ReflectionTestUtils.setField(filter, "frontendUrl", "https://shop.example");
            MockHttpServletRequest request = tokenRequest();
            request.setParameter("grant_type", "refresh_token");
            request.addHeader("Referer", referer);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(esperado);
            verify(chain, times(esperado == 200 ? 1 : 0)).doFilter(request, response);
        }

        @ParameterizedTest
        @CsvSource({"http://shop.example:80,http://SHOP.example", "https://shop.example,https://shop.example:443"})
        void aceitaPortaPadraoEquivalente(String frontend, String origin) throws Exception {
            ReflectionTestUtils.setField(filter, "frontendUrl", frontend);
            MockHttpServletRequest request = tokenRequest();
            request.setParameter("grant_type", "refresh_token");
            request.addHeader("Origin", origin);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);
            filter.doFilter(request, response, chain);
            verify(chain).doFilter(request, response);
        }

        @Test
        @DisplayName("Deve aceitar quando Origin for exatamente frontendUrl com barra no final")
        void deveAceitarQuandoOriginComBarraFinal() throws ServletException, IOException {
            MockHttpServletRequest request = tokenRequest();
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
            MockHttpServletRequest request = tokenRequest();
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
            MockHttpServletRequest request = tokenRequest();
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
            MockHttpServletRequest request = tokenRequest();
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

    private static MockHttpServletRequest tokenRequest() {
        return requestComPath("POST", "/oauth2/token", "/oauth2/token");
    }

    private static MockHttpServletRequest requestComPath(String method, String requestUri, String servletPath) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, requestUri);
        request.setServletPath(servletPath);
        return request;
    }
}
