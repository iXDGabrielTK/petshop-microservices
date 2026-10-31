package auth.security.handler;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("CookieClearingLogoutHandler — Testes Unitários de Expiração de Cookies no Logout")
class CookieClearingLogoutHandlerTest {

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
    }
}
