package gateway.config;

import common.security.RsaKeyUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;

import java.security.KeyPair;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GatewaySecurityConfig — Testes Unitários de Segurança Reativa WebFlux")
class GatewaySecurityConfigTest {

    private GatewaySecurityConfig gatewaySecurityConfig;
    private String validPublicKeyPem;
    private KeyPair keyPair;

    @BeforeEach
    void setUp() {
        gatewaySecurityConfig = new GatewaySecurityConfig();

        keyPair = RsaKeyUtils.generateKeyPair();
        String base64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        validPublicKeyPem = "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----";

        ReflectionTestUtils.setField(gatewaySecurityConfig, "publicKeyString", validPublicKeyPem);
        ReflectionTestUtils.setField(gatewaySecurityConfig, "corsAllowedOrigins", "http://localhost:3000,http://localhost:5173");
        ReflectionTestUtils.setField(gatewaySecurityConfig, "corsAllowedMethods", "GET,POST,PUT,DELETE,OPTIONS");
        ReflectionTestUtils.setField(gatewaySecurityConfig, "corsAllowedHeaders", "Authorization,Content-Type,X-Requested-With");
        ReflectionTestUtils.setField(gatewaySecurityConfig, "corsAllowCredentials", true);
    }

    @Nested
    @DisplayName("jwtDecoder()")
    class JwtDecoderTests {

        @Test
        @DisplayName("Deve construir NimbusReactiveJwtDecoder a partir de chave pública RSA válida")
        void deveConstruirReactiveJwtDecoderComChaveValida() {
            ReactiveJwtDecoder jwtDecoder = gatewaySecurityConfig.jwtDecoder();

            assertThat(jwtDecoder).isNotNull();
            assertThat(jwtDecoder).isInstanceOf(NimbusReactiveJwtDecoder.class);
        }

        @Test
        @DisplayName("Deve lançar IllegalStateException quando chave pública for inválida")
        void deveLancarExcecaoQuandoChaveInvalida() {
            ReflectionTestUtils.setField(gatewaySecurityConfig, "publicKeyString", "chave-invalida-sem-formato-pem");

            assertThatThrownBy(() -> gatewaySecurityConfig.jwtDecoder())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Erro ao converter chave pública RSA");
        }
    }

    @Nested
    @DisplayName("corsConfigurationSource()")
    class CorsConfigurationSourceTests {

        @Test
        @DisplayName("Deve registrar UrlBasedCorsConfigurationSource com mapeamento para /**")
        void deveRegistrarUrlBasedCorsConfigurationSource() {
            CorsConfigurationSource source = gatewaySecurityConfig.corsConfigurationSource();

            assertThat(source).isNotNull();
            assertThat(source).isInstanceOf(UrlBasedCorsConfigurationSource.class);
        }

        @Test
        @DisplayName("Deve configurar origens, métodos, headers e credenciais corretamente para rotas mapeadas")
        void deveConfigurarPropriedadesCorsCorretamente() {
            CorsConfigurationSource source = gatewaySecurityConfig.corsConfigurationSource();

            ServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/usuarios/me").build()
            );

            CorsConfiguration config = source.getCorsConfiguration(exchange);
            assertThat(config).isNotNull();
            assertThat(config.getAllowedOrigins()).containsExactly("http://localhost:3000", "http://localhost:5173");
            assertThat(config.getAllowedMethods()).containsExactly("GET", "POST", "PUT", "DELETE", "OPTIONS");
            assertThat(config.getAllowedHeaders()).containsExactly("Authorization", "Content-Type", "X-Requested-With");
            assertThat(config.getAllowCredentials()).isTrue();
        }

        @Test
        @DisplayName("Deve aplicar a mesma configuração CORS em rotas diversas (pattern /**)")
        void deveAplicarCorsEmQualquerRota() {
            CorsConfigurationSource source = gatewaySecurityConfig.corsConfigurationSource();

            ServerWebExchange exchangeVendas = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/vendas/10").build()
            );
            ServerWebExchange exchangeProdutos = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/produtos").build()
            );

            CorsConfiguration configVendas = source.getCorsConfiguration(exchangeVendas);
            CorsConfiguration configProdutos = source.getCorsConfiguration(exchangeProdutos);

            assertThat(configVendas).isNotNull();
            assertThat(configProdutos).isNotNull();
            assertThat(configVendas.getAllowedOrigins()).isEqualTo(configProdutos.getAllowedOrigins());
        }
    }

    @Nested
    @DisplayName("springSecurityFilterChain()")
    class SpringSecurityFilterChainTests {

        private org.springframework.test.web.reactive.server.WebTestClient client;

        @BeforeEach
        void setUpChain() {
            ServerHttpSecurity http = ServerHttpSecurity.http();
            SecurityWebFilterChain filterChain = gatewaySecurityConfig.springSecurityFilterChain(http);
            java.util.List<org.springframework.web.server.WebFilter> webFilters =
                    filterChain.getWebFilters().collectList().block();

            assertThat(webFilters).isNotNull();

            client = org.springframework.test.web.reactive.server.WebTestClient.bindToRouterFunction(
                    org.springframework.web.reactive.function.server.RouterFunctions.route()
                            .POST("/usuarios/register", req -> org.springframework.web.reactive.function.server.ServerResponse.ok().bodyValue("registered"))
                            .GET("/usuarios/me", req -> org.springframework.web.reactive.function.server.ServerResponse.ok().bodyValue("usuarios_ok"))
                            .GET("/oauth2/token", req -> org.springframework.web.reactive.function.server.ServerResponse.ok().bodyValue("token_ok"))
                            .GET("/vendas/10", req -> org.springframework.web.reactive.function.server.ServerResponse.ok().bodyValue("vendas_ok"))
                            .GET("/produtos/20", req -> org.springframework.web.reactive.function.server.ServerResponse.ok().bodyValue("produtos_ok"))
                            .GET("/movimentacoes/30", req -> org.springframework.web.reactive.function.server.ServerResponse.ok().bodyValue("mov_ok"))
                            .GET("/dashboard/stats", req -> org.springframework.web.reactive.function.server.ServerResponse.ok().bodyValue("stats_ok"))
                            .GET("/actuator/health", req -> org.springframework.web.reactive.function.server.ServerResponse.ok().bodyValue("healthy"))
                            .GET("/v3/api-docs/swagger-config", req -> org.springframework.web.reactive.function.server.ServerResponse.ok().bodyValue("docs_ok"))
                            .POST("/pagamentos/checkout", req -> org.springframework.web.reactive.function.server.ServerResponse.ok().bodyValue("pago"))
                            .GET("/rota-privada", req -> org.springframework.web.reactive.function.server.ServerResponse.ok().bodyValue("privado"))
                            .build()
            )
            .webFilter(webFilters.toArray(new org.springframework.web.server.WebFilter[0]))
            .build();
        }

        @Test
        @DisplayName("Deve permitir rotas públicas sem autenticação")
        void devePermitirRotasPublicasSemAutenticacao() {
            client.post().uri("/usuarios/register").exchange().expectStatus().isOk();
            client.get().uri("/usuarios/me").exchange().expectStatus().isOk();
            client.get().uri("/oauth2/token").exchange().expectStatus().isOk();
            client.get().uri("/vendas/10").exchange().expectStatus().isOk();
            client.get().uri("/produtos/20").exchange().expectStatus().isOk();
            client.get().uri("/movimentacoes/30").exchange().expectStatus().isOk();
            client.get().uri("/dashboard/stats").exchange().expectStatus().isOk();
            client.get().uri("/actuator/health").exchange().expectStatus().isOk();
            client.get().uri("/v3/api-docs/swagger-config").exchange().expectStatus().isOk();
        }

        @Test
        @DisplayName("Deve rejeitar rota privada com 401 Unauthorized quando não houver Bearer token")
        void deveRejeitarRotaPrivadaSemToken() {
            client.get().uri("/rota-privada").exchange().expectStatus().isUnauthorized();
            client.post().uri("/pagamentos/checkout").exchange().expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("Deve rejeitar rota privada com 401 Unauthorized quando Bearer token for inválido")
        void deveRejeitarRotaPrivadaComTokenInvalido() {
            client.get().uri("/rota-privada")
                    .header("Authorization", "Bearer token-invalido-malformado")
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("Deve permitir acesso à rota privada quando fornecido Bearer JWT assinado válido")
        void devePermitirRotaPrivadaComJwtValido() throws Exception {
            String token = createSignedJwt("user123", java.time.Duration.ofMinutes(15));

            client.get().uri("/rota-privada")
                    .header("Authorization", "Bearer " + token)
                    .exchange()
                    .expectStatus().isOk();
        }

        @Test
        @DisplayName("Deve injetar header Content-Security-Policy em respostas HTTP")
        void deveInjetarHeaderContentSecurityPolicy() {
            client.get().uri("/actuator/health").exchange()
                    .expectStatus().isOk()
                    .expectHeader().value("Content-Security-Policy", csp ->
                            assertThat(csp).contains("default-src 'self'").contains("frame-ancestors 'none'")
                    );
        }

        @Test
        @DisplayName("Deve conter CorsWebFilter e HttpHeaderWriterWebFilter configurados na cadeia de filtros de segurança")
        void deveConterFiltrosDeSegurancaNaCadeia() {
            ServerHttpSecurity http = ServerHttpSecurity.http();
            SecurityWebFilterChain filterChain = gatewaySecurityConfig.springSecurityFilterChain(http);
            java.util.List<org.springframework.web.server.WebFilter> webFilters =
                    filterChain.getWebFilters().collectList().block();

            assertThat(webFilters).isNotNull();
            assertThat(webFilters).anyMatch(filter -> filter instanceof org.springframework.web.cors.reactive.CorsWebFilter);
            assertThat(webFilters).anyMatch(filter -> filter instanceof org.springframework.security.web.server.header.HttpHeaderWriterWebFilter);
        }
    }

    private String createSignedJwt(String subject, java.time.Duration validFor) throws Exception {
        com.nimbusds.jwt.JWTClaimsSet claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
                .subject(subject)
                .issuer("https://auth-service")
                .issueTime(new java.util.Date())
                .expirationTime(new java.util.Date(System.currentTimeMillis() + validFor.toMillis()))
                .build();

        com.nimbusds.jose.JWSHeader header = new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256).build();
        com.nimbusds.jwt.SignedJWT signedJWT = new com.nimbusds.jwt.SignedJWT(header, claims);
        signedJWT.sign(new com.nimbusds.jose.crypto.RSASSASigner((java.security.interfaces.RSAPrivateKey) keyPair.getPrivate()));
        return signedJWT.serialize();
    }
}
