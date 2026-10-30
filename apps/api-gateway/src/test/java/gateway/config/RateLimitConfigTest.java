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

@DisplayName("RateLimitConfig — Testes Unitários do KeyResolver para Rate Limiting")
class RateLimitConfigTest {

    private RateLimitConfig rateLimitConfig;
    private KeyResolver keyResolver;

    @BeforeEach
    void setUp() {
        rateLimitConfig = new RateLimitConfig();
        keyResolver = rateLimitConfig.userKeyResolver();
    }

    @Nested
    @DisplayName("userKeyResolver() — Resolução via Proxies Reversos")
    class ResolucaoProxyTests {

        @Test
        @DisplayName("Deve extrair o primeiro IP do cabeçalho X-Forwarded-For quando fornecido múltiplos IPs")
        void deveExtrairPrimeiroIpDoXForwardedFor() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/produtos")
                    .header("X-Forwarded-For", "203.0.113.195, 70.41.3.18, 150.172.238.178")
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(keyResolver.resolve(exchange))
                    .expectNext("203.0.113.195")
                    .verifyComplete();
        }

        @Test
        @DisplayName("Deve extrair o IP do cabeçalho X-Real-IP quando X-Forwarded-For estiver ausente")
        void deveExtrairIpDoXRealIp() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/produtos")
                    .header("X-Real-IP", "198.51.100.42")
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(keyResolver.resolve(exchange))
                    .expectNext("198.51.100.42")
                    .verifyComplete();
        }

        @Test
        @DisplayName("Deve preferir X-Forwarded-For sobre X-Real-IP quando ambos existirem")
        void devePreferirXffSobreXRealIp() {
            MockServerHttpRequest request = MockServerHttpRequest.get("/produtos")
                    .header("X-Forwarded-For", "203.0.113.1")
                    .header("X-Real-IP", "198.51.100.2")
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(keyResolver.resolve(exchange))
                    .expectNext("203.0.113.1")
                    .verifyComplete();
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
