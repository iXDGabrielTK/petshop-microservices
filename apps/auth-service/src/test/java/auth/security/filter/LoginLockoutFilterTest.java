/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package auth.security.filter;

import auth.security.LoginAttemptService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LoginLockoutFilter — Testes Unitários do Filtro de Bloqueio Pré-Autenticação")
class LoginLockoutFilterTest {

    @Mock
    private LoginAttemptService loginAttemptService;

    @Mock
    private FilterChain filterChain;

    private LoginLockoutFilter filter;

    @BeforeEach
    void setUp() {
        filter = new LoginLockoutFilter(loginAttemptService);
    }

    @Test
    @DisplayName("Deve redirecionar para /login?error sem invocar cadeia quando POST /login estiver bloqueado")
    void deveBloquearPostLoginQuandoEmLockout() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setParameter("username", "user@petshop.com");
        request.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(loginAttemptService.isBlocked("user@petshop.com", "10.0.0.5")).thenReturn(true);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
        verifyNoInteractions(filterChain);
    }

    @Test
    @DisplayName("Deve prosseguir na cadeia quando POST /login não estiver bloqueado")
    void deveProsseguirQuandoNaoBloqueado() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setParameter("username", "user@petshop.com");
        request.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(loginAttemptService.isBlocked("user@petshop.com", "10.0.0.5")).thenReturn(false);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Não deve consultar bloqueio em GET /login ou em outras rotas")
    void naoDeveConsultarBloqueioEmGetOuOutrasRotas() throws Exception {
        MockHttpServletRequest getLogin = new MockHttpServletRequest("GET", "/login");
        MockHttpServletResponse getRes = new MockHttpServletResponse();
        MockHttpServletRequest postOther = new MockHttpServletRequest("POST", "/usuarios/register");
        MockHttpServletResponse postRes = new MockHttpServletResponse();

        filter.doFilter(getLogin, getRes, filterChain);
        filter.doFilter(postOther, postRes, filterChain);

        verifyNoInteractions(loginAttemptService);
        verify(filterChain).doFilter(getLogin, getRes);
        verify(filterChain).doFilter(postOther, postRes);
    }
}
