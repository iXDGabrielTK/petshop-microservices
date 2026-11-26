/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.test.StepVerifier;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RateLimitConfig — Testes Unitários do KeyResolver para Rate Limiting")
class RateLimitConfigTest {

    private KeyResolver keyResolver;

    @BeforeEach
    void setUp() {
        keyResolver = new RateLimitConfig(0).userKeyResolver();
    }

    @Nested
    @DisplayName("userKeyResolver() — Proteção contra Spoofing e Proxies Confiáveis")
    class ResolucaoProxyTests {

        @Test
        @DisplayName("Com hops=0, deve ignorar X-Forwarded-For e X-Real-IP forjados e usar remoteAddress do socket")
        void deveIgnorarHeadersForjadosQuandoHopsZero() throws Exception {
            InetSocketAddress socketAddress = new InetSocketAddress(InetAddress.getByName("192.168.1.50"), 54321);
            MockServerHttpRequest request = MockServerHttpRequest.get("/produtos")
                    .header("X-Forwarded-For", "1.2.3.4, 5.6.7.8")
                    .header("X-Real-IP", "198.51.100.42")
                    .remoteAddress(socketAddress)
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(keyResolver.resolve(exchange))
                    .expectNext("192.168.1.50")
                    .verifyComplete();
        }

        @Test
        @DisplayName("Com hops=0 e remoteAddress nulo, deve ignorar X-Forwarded-For e X-Real-IP forjados e usar chave anônima")
        void deveIgnorarHeadersForjadosComRemoteAddressNuloQuandoHopsZero() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/produtos")
                    .header("X-Forwarded-For", "1.2.3.4")
                    .header("X-Real-IP", "198.51.100.42")
                    .header("User-Agent", "TestClient/1.0")
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(keyResolver.resolve(exchange))
                    .assertNext(key -> assertThat(key).startsWith(RateLimitConfig.ANONYMOUS_PREFIX))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Com hops=1, deve usar o último IP confiável do X-Forwarded-For ignorando prefixo forjado pelo cliente")
        void deveResolverIndiceConfiavelQuandoHopsUm() throws Exception {
            KeyResolver resolver = new RateLimitConfig(1).userKeyResolver();
            MockServerHttpRequest request = MockServerHttpRequest.get("/produtos")
                    .header("X-Forwarded-For", "1.1.1.1, 203.0.113.195")
                    .remoteAddress(new InetSocketAddress(InetAddress.getByName("10.0.0.10"), 443))
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(resolver.resolve(exchange))
                    .expectNext("203.0.113.195")
                    .verifyComplete();
        }

        @Test
        @DisplayName("Com hops=1 e único IP no X-Forwarded-For, deve resolver esse IP")
        void deveResolverUnicoIpNoXffQuandoHopsUm() throws Exception {
            KeyResolver resolver = new RateLimitConfig(1).userKeyResolver();
            MockServerHttpRequest request = MockServerHttpRequest.get("/produtos")
                    .header("X-Forwarded-For", "203.0.113.195")
                    .remoteAddress(new InetSocketAddress(InetAddress.getByName("10.0.0.10"), 443))
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(resolver.resolve(exchange))
                    .expectNext("203.0.113.195")
                    .verifyComplete();
        }

        @Test
        @DisplayName("Com hops=1 e sem X-Forwarded-For, deve ignorar X-Real-IP e fazer fallback para remoteAddress")
        void deveIgnorarXRealIpEFazerFallbackParaRemoteAddressQuandoHopsUm() throws Exception {
            KeyResolver resolver = new RateLimitConfig(1).userKeyResolver();
            MockServerHttpRequest request = MockServerHttpRequest.get("/produtos")
                    .header("X-Real-IP", "198.51.100.42")
                    .remoteAddress(new InetSocketAddress(InetAddress.getByName("10.0.0.10"), 443))
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(resolver.resolve(exchange))
                    .expectNext("10.0.0.10")
                    .verifyComplete();
        }

        @Test
        @DisplayName("Com hops=2, deve resolver o segundo índice da direita para a esquerda em X-Forwarded-For")
        void deveResolverSegundoIndiceConfiavelQuandoHopsDois() throws Exception {
            KeyResolver resolver = new RateLimitConfig(2).userKeyResolver();
            MockServerHttpRequest request = MockServerHttpRequest.get("/produtos")
                    .header("X-Forwarded-For", "1.1.1.1, 203.0.113.195, 70.41.3.18")
                    .remoteAddress(new InetSocketAddress(InetAddress.getByName("10.0.0.10"), 443))
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(resolver.resolve(exchange))
                    .expectNext("203.0.113.195")
                    .verifyComplete();
        }

        @Test
        @DisplayName("Com hops=1 e múltiplos headers X-Forwarded-For, deve descartar headers ambíguos e fazer fallback para remoteAddress")
        void deveDescartarMultiplosHeadersXffEFazerFallbackParaRemoteAddress() throws Exception {
            KeyResolver resolver = new RateLimitConfig(1).userKeyResolver();
            MockServerHttpRequest request = MockServerHttpRequest.get("/produtos")
                    .header("X-Forwarded-For", "1.1.1.1")
                    .header("X-Forwarded-For", "203.0.113.195")
                    .remoteAddress(new InetSocketAddress(InetAddress.getByName("10.0.0.10"), 443))
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(resolver.resolve(exchange))
                    .expectNext("10.0.0.10")
                    .verifyComplete();
        }

        @Test
        @DisplayName("Com hops=1 e IPv6 no X-Forwarded-For, deve resolver o IPv6 confiável sem porta")
        void deveResolverIpv6ConfiavelNoXffQuandoHopsUm() throws Exception {
            KeyResolver resolver = new RateLimitConfig(1).userKeyResolver();
            MockServerHttpRequest request = MockServerHttpRequest.get("/produtos")
                    .header("X-Forwarded-For", "1.1.1.1, 2001:db8::1")
                    .remoteAddress(new InetSocketAddress(InetAddress.getByName("10.0.0.10"), 443))
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(resolver.resolve(exchange))
                    .expectNext("2001:db8:0:0:0:0:0:1")
                    .verifyComplete();
        }

        @Test
        @DisplayName("Com hops=1 e X-Forwarded-For vazio ou em branco, deve fazer fallback para remoteAddress")
        void deveFazerFallbackParaRemoteAddressQuandoXffEmBrancoOuVazio() throws Exception {
            KeyResolver resolver = new RateLimitConfig(1).userKeyResolver();
            MockServerHttpRequest emptyRequest = MockServerHttpRequest.get("/produtos")
                    .header("X-Forwarded-For", "")
                    .remoteAddress(new InetSocketAddress(InetAddress.getByName("10.0.0.10"), 443))
                    .build();
            MockServerHttpRequest blankRequest = MockServerHttpRequest.get("/produtos")
                    .header("X-Forwarded-For", "   ")
                    .remoteAddress(new InetSocketAddress(InetAddress.getByName("10.0.0.10"), 443))
                    .build();

            StepVerifier.create(resolver.resolve(MockServerWebExchange.from(emptyRequest)))
                    .expectNext("10.0.0.10")
                    .verifyComplete();
            StepVerifier.create(resolver.resolve(MockServerWebExchange.from(blankRequest)))
                    .expectNext("10.0.0.10")
                    .verifyComplete();
        }

        @Test
        @DisplayName("Deve rejeitar trustedProxyHops negativo no construtor")
        void deveRejeitarHopsNegativo() {
            assertThatThrownBy(() -> new RateLimitConfig(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("trusted-proxy-hops");
        }
    }

    @Nested
    @DisplayName("userKeyResolver() — Resolução Direta de Endereço IP (Socket)")
    class ResolucaoIpTests {

        @Test
        @DisplayName("Deve resolver deterministamente o endereço IP IPv4 a partir do remoteAddress")
        void deveResolverIpIpv4() throws Exception {
            InetAddress inetAddress = InetAddress.getByName("192.168.1.100");
            InetSocketAddress socketAddress = new InetSocketAddress(inetAddress, 54321);

            MockServerHttpRequest request = MockServerHttpRequest.get("/produtos")
                    .remoteAddress(socketAddress)
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(keyResolver.resolve(exchange))
                    .expectNext("192.168.1.100")
                    .verifyComplete();
        }

        @Test
        @DisplayName("Deve resolver deterministamente o endereço IP IPv6 a partir do remoteAddress")
        void deveResolverIpIpv6() throws Exception {
            InetAddress inetAddress = InetAddress.getByName("::1");
            InetSocketAddress socketAddress = new InetSocketAddress(inetAddress, 54321);

            MockServerHttpRequest request = MockServerHttpRequest.get("/vendas")
                    .remoteAddress(socketAddress)
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(keyResolver.resolve(exchange))
                    .assertNext(ip -> assertThat(ip).isIn("0:0:0:0:0:0:0:1", "::1", "127.0.0.1"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Deve retornar chaves distintas para endereços IP diferentes")
        void deveRetornarChavesDistintasParaIpsDiferentes() throws Exception {
            ServerWebExchange exchange1 = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api")
                            .remoteAddress(new InetSocketAddress(InetAddress.getByName("10.0.0.1"), 80))
                            .build()
            );

            ServerWebExchange exchange2 = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api")
                            .remoteAddress(new InetSocketAddress(InetAddress.getByName("10.0.0.2"), 80))
                            .build()
            );

            String key1 = keyResolver.resolve(exchange1).block();
            String key2 = keyResolver.resolve(exchange2).block();

            assertThat(key1).isEqualTo("10.0.0.1");
            assertThat(key2).isEqualTo("10.0.0.2");
            assertThat(key1).isNotEqualTo(key2);
        }

        @Test
        @DisplayName("Deve garantir determinismo retornando a mesma chave em invocações repetidas")
        void deveGarantirDeterminismo() throws Exception {
            ServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api")
                            .remoteAddress(new InetSocketAddress(InetAddress.getByName("172.16.0.50"), 8080))
                            .build()
            );

            String first = keyResolver.resolve(exchange).block();
            String second = keyResolver.resolve(exchange).block();

            assertThat(first).isEqualTo("172.16.0.50");
            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("Deve ignorar porta efêmera gerando a mesma chave para o mesmo IPv4 e IPv6")
        void deveIgnorarPortaEfemeraParaMesmoIpIpv4EIpv6() throws Exception {
            ServerWebExchange ipv4Conn1 = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api")
                            .remoteAddress(new InetSocketAddress(InetAddress.getByName("192.168.1.50"), 54321))
                            .build()
            );
            ServerWebExchange ipv4Conn2 = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api")
                            .remoteAddress(new InetSocketAddress(InetAddress.getByName("192.168.1.50"), 54322))
                            .build()
            );
            ServerWebExchange ipv6Conn1 = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api")
                            .remoteAddress(new InetSocketAddress(InetAddress.getByName("::1"), 54321))
                            .build()
            );
            ServerWebExchange ipv6Conn2 = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api")
                            .remoteAddress(new InetSocketAddress(InetAddress.getByName("::1"), 54322))
                            .build()
            );

            String ipv4Key1 = keyResolver.resolve(ipv4Conn1).block();
            String ipv4Key2 = keyResolver.resolve(ipv4Conn2).block();
            String ipv6Key1 = keyResolver.resolve(ipv6Conn1).block();
            String ipv6Key2 = keyResolver.resolve(ipv6Conn2).block();

            assertThat(ipv4Key1).isEqualTo("192.168.1.50").isEqualTo(ipv4Key2);
            assertThat(ipv6Key1).isEqualTo("0:0:0:0:0:0:0:1").isEqualTo(ipv6Key2);
            assertThat(ipv4Key1).doesNotContain("54321");
            assertThat(ipv6Key1).doesNotContain("54321");
        }
    }

    @Nested
    @DisplayName("userKeyResolver() — Mitigação de DoS e Tratamento de Borda")
    class MitigacaoDosETestTratamentoDefensivo {

        @Test
        @DisplayName("Deve resolver defensivamente para 'anonymous' quando exchange for nulo")
        void deveTratarExchangeNulo() {
            StepVerifier.create(keyResolver.resolve(null))
                    .expectNext(RateLimitConfig.ANONYMOUS_KEY)
                    .verifyComplete();
        }

        @Test
        @DisplayName("Deve resolver defensivamente para 'anonymous' quando exchange.getRequest() for nulo")
        void deveTratarRequestNulo() {
            ServerWebExchange mockExchange = org.mockito.Mockito.mock(ServerWebExchange.class);
            org.mockito.Mockito.when(mockExchange.getRequest()).thenReturn(null);

            StepVerifier.create(keyResolver.resolve(mockExchange))
                    .expectNext(RateLimitConfig.ANONYMOUS_KEY)
                    .verifyComplete();
        }

        @Test
        @DisplayName("Deve particionar chave anônima por rota e User-Agent quando remoteAddress for nulo evitando DoS global")
        void deveParticionarChaveAnonimaQuandoRemoteAddressNulo() {
            MockServerHttpRequest request1 = MockServerHttpRequest.get("/produtos")
                    .header("User-Agent", "AppMobile/1.0")
                    .build();
            MockServerHttpRequest request2 = MockServerHttpRequest.get("/vendas")
                    .header("User-Agent", "AppWeb/2.0")
                    .build();

            String key1 = keyResolver.resolve(MockServerWebExchange.from(request1)).block();
            String key2 = keyResolver.resolve(MockServerWebExchange.from(request2)).block();

            assertThat(key1).startsWith(RateLimitConfig.ANONYMOUS_PREFIX);
            assertThat(key2).startsWith(RateLimitConfig.ANONYMOUS_PREFIX);
            assertThat(key1).isNotEqualTo(key2);
        }

        @Test
        @DisplayName("Deve resolver defensivamente pelo hostName quando InetSocketAddress for não-resolvido")
        void deveTratarInetSocketAddressNaoResolvido() {
            InetSocketAddress unresolved = InetSocketAddress.createUnresolved("cliente-gateway.local", 8080);
            assertThat(unresolved.getAddress()).isNull();
            assertThat(unresolved.getHostString()).isEqualTo("cliente-gateway.local");

            MockServerHttpRequest request = MockServerHttpRequest.get("/api")
                    .remoteAddress(unresolved)
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(keyResolver.resolve(exchange))
                    .expectNext("cliente-gateway.local")
                    .verifyComplete();
        }
    }
}
