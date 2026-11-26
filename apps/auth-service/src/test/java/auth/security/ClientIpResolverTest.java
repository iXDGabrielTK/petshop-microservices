/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package auth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ClientIpResolver — Testes Unitários de Resolução Segura de IP")
class ClientIpResolverTest {

    @Test
    @DisplayName("Com hops=0, deve ignorar X-Forwarded-For e X-Real-IP e usar remoteAddr do socket")
    void deveIgnorarHeadersQuandoHopsZero() {
        ClientIpResolver resolver = new ClientIpResolver(0);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setRemoteAddr("10.20.30.40");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8");
        request.addHeader("X-Real-IP", "198.51.100.42");

        assertThat(resolver.resolve(request)).isEqualTo("10.20.30.40");
    }

    @Test
    @DisplayName("Deve desembrulhar request wrappers como ForwardedHeaderFilter para ler o socket real")
    void deveDesembrulharWrapperParaLerSocketReal() {
        ClientIpResolver resolver = new ClientIpResolver(0);
        MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/login");
        raw.setRemoteAddr("172.18.0.5");

        HttpServletRequest spoofedWrapper = new HttpServletRequestWrapper(raw) {
            @Override
            public String getRemoteAddr() {
                return "1.1.1.1";
            }
        };

        assertThat(resolver.resolve(spoofedWrapper)).isEqualTo("172.18.0.5");
    }

    @Test
    @DisplayName("Com hops=1, deve resolver o último IP da cadeia X-Forwarded-For ignorando prefixo forjado")
    void deveResolverUltimoIpQuandoHopsUm() {
        ClientIpResolver resolver = new ClientIpResolver(1);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setRemoteAddr("10.0.0.10");
        request.addHeader("X-Forwarded-For", "1.1.1.1, 203.0.113.195");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.195");
    }

    @Test
    @DisplayName("Com hops=2, deve resolver o segundo IP pela direita na cadeia X-Forwarded-For")
    void deveResolverSegundoIpPelaDireitaQuandoHopsDois() {
        ClientIpResolver resolver = new ClientIpResolver(2);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setRemoteAddr("10.0.0.10");
        request.addHeader("X-Forwarded-For", "1.1.1.1, 2001:db8::1, 70.41.3.18");

        assertThat(resolver.resolve(request)).isEqualTo("2001:db8::1");
    }

    @Test
    @DisplayName("Com hops=1, deve unificar múltiplos headers X-Forwarded-For, ignorar entradas vazias e fazer fallback só quando vazio")
    void deveUnificarMultiplosHeadersXffEIgnorarEntradasVazias() {
        ClientIpResolver resolver = new ClientIpResolver(1);

        MockHttpServletRequest multiHeader = new MockHttpServletRequest("POST", "/login");
        multiHeader.setRemoteAddr("10.0.0.10");
        multiHeader.addHeader("X-Forwarded-For", "1.1.1.1");
        multiHeader.addHeader("X-Forwarded-For", "203.0.113.195");
        assertThat(resolver.resolve(multiHeader)).isEqualTo("203.0.113.195");

        MockHttpServletRequest trailingComma = new MockHttpServletRequest("POST", "/login");
        trailingComma.setRemoteAddr("10.0.0.10");
        trailingComma.addHeader("X-Forwarded-For", "1.1.1.1, ");
        assertThat(resolver.resolve(trailingComma)).isEqualTo("1.1.1.1");

        MockHttpServletRequest onlyBlankTokens = new MockHttpServletRequest("POST", "/login");
        onlyBlankTokens.setRemoteAddr("10.0.0.10");
        onlyBlankTokens.addHeader("X-Forwarded-For", " , ");
        assertThat(resolver.resolve(onlyBlankTokens)).isEqualTo("10.0.0.10");

        MockHttpServletRequest emptyHeader = new MockHttpServletRequest("POST", "/login");
        emptyHeader.setRemoteAddr("10.0.0.10");
        emptyHeader.addHeader("X-Forwarded-For", "");
        assertThat(resolver.resolve(emptyHeader)).isEqualTo("10.0.0.10");
    }

    @Test
    @DisplayName("Deve resolver hops=1 e hops=2 corretamente com múltiplos headers XFF e vírgulas vazias")
    void deveResolverHopsUmEDoisComMultiplosHeadersEVirgulasVazias() {
        ClientIpResolver hops1 = new ClientIpResolver(1);
        ClientIpResolver hops2 = new ClientIpResolver(2);

        MockHttpServletRequest multiLine = new MockHttpServletRequest("POST", "/login");
        multiLine.setRemoteAddr("10.0.0.10");
        multiLine.addHeader("X-Forwarded-For", "1.2.3.4");
        multiLine.addHeader("X-Forwarded-For", "5.6.7.8");
        assertThat(hops1.resolve(multiLine)).isEqualTo("5.6.7.8");
        assertThat(hops2.resolve(multiLine)).isEqualTo("1.2.3.4");

        MockHttpServletRequest extraCommasSingleLine = new MockHttpServletRequest("POST", "/login");
        extraCommasSingleLine.setRemoteAddr("10.0.0.10");
        extraCommasSingleLine.addHeader("X-Forwarded-For", "1.2.3.4,, 5.6.7.8,");
        assertThat(hops1.resolve(extraCommasSingleLine)).isEqualTo("5.6.7.8");
        assertThat(hops2.resolve(extraCommasSingleLine)).isEqualTo("1.2.3.4");

        MockHttpServletRequest extraCommasMultiLine = new MockHttpServletRequest("POST", "/login");
        extraCommasMultiLine.setRemoteAddr("10.0.0.10");
        extraCommasMultiLine.addHeader("X-Forwarded-For", "1.2.3.4, ");
        extraCommasMultiLine.addHeader("X-Forwarded-For", ",5.6.7.8");
        assertThat(hops1.resolve(extraCommasMultiLine)).isEqualTo("5.6.7.8");
        assertThat(hops2.resolve(extraCommasMultiLine)).isEqualTo("1.2.3.4");
    }

    @Test
    @DisplayName("Deve configurar server.forward-headers-strategy como none por padrão e aceitar override via variável")
    void deveDefinirForwardHeadersStrategyComoNonePorPadrao() throws IOException {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addLast(new ResourcePropertySource("classpath:application.properties"));
        assertThat(environment.getRequiredProperty("server.forward-headers-strategy")).isEqualTo("none");

        environment.setProperty("SERVER_FORWARD_HEADERS_STRATEGY", "native");
        assertThat(environment.getRequiredProperty("server.forward-headers-strategy")).isEqualTo("native");
    }

    @Test
    @DisplayName("Deve retornar 'unknown' para request nulo ou remoteAddr em branco e rejeitar hops negativo")
    void deveTratarEntradasNulasERejeitarHopsNegativo() {
        ClientIpResolver resolver = new ClientIpResolver(0);
        assertThat(resolver.resolve(null)).isEqualTo(ClientIpResolver.UNKNOWN_IP);

        MockHttpServletRequest blankAddr = new MockHttpServletRequest("POST", "/login");
        blankAddr.setRemoteAddr("   ");
        assertThat(resolver.resolve(blankAddr)).isEqualTo(ClientIpResolver.UNKNOWN_IP);

        assertThatThrownBy(() -> new ClientIpResolver(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trusted-proxy-hops");
    }
}
