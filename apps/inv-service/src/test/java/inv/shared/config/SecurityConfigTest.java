package inv.shared.config;

import common.security.RsaKeyUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SecurityConfig (inv-service) — Testes Unitários de Beans e Regras de Segurança")
class SecurityConfigTest {

    private SecurityConfig securityConfig;

    @BeforeEach
    void setUp() {
        securityConfig = new SecurityConfig();
    }

    @Nested
    @DisplayName("jwtDecoder()")
    class JwtDecoderTests {

        @Test
        @DisplayName("Deve construir JwtDecoder com sucesso quando chave pública RSA for configurada")
        void deveConstruirJwtDecoderComChaveRsa() {
            KeyPair keyPair = RsaKeyUtils.generateKeyPair();
            String pubKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

            ReflectionTestUtils.setField(securityConfig, "publicKeyString", pubKeyBase64);

            JwtDecoder decoder = securityConfig.jwtDecoder();
            assertThat(decoder).isNotNull();
        }
    }

    @Nested
    @DisplayName("jwtAuthenticationConverter()")
    class JwtAuthenticationConverterTests {

        @Test
        @DisplayName("Deve converter scopes e roles quando ambos estiverem presentes no JWT")
        void deveConverterScopesERoles() {
            JwtAuthenticationConverter converter = securityConfig.jwtAuthenticationConverter();

            Jwt jwt = new Jwt(
                    "token-valor",
                    Instant.now(),
                    Instant.now().plusSeconds(3600),
                    Map.of("alg", "none"),
                    Map.of(
                            "sub", "user@petshop.com",
                            "scope", "read write",
                            "roles", List.of("ROLE_ADMIN", "ROLE_USER")
                    )
            );

            var auth = converter.convert(jwt);
            assertThat(auth).isNotNull();

            Collection<String> authorities = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .toList();

            assertThat(authorities).containsExactlyInAnyOrder(
                    "SCOPE_read",
                    "SCOPE_write",
                    "ROLE_ADMIN",
                    "ROLE_USER"
            );
        }

        @Test
        @DisplayName("Deve converter apenas scopes quando claim roles não existir no JWT")
        void deveConverterApenasScopesQuandoRolesAusente() {
            JwtAuthenticationConverter converter = securityConfig.jwtAuthenticationConverter();

            Jwt jwt = new Jwt(
                    "token-valor",
                    Instant.now(),
                    Instant.now().plusSeconds(3600),
                    Map.of("alg", "none"),
                    Map.of(
                            "sub", "user@petshop.com",
                            "scope", "read"
                    )
            );

            var auth = converter.convert(jwt);
            assertThat(auth).isNotNull();

            Collection<String> authorities = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .toList();

            assertThat(authorities).containsExactly("SCOPE_read");
        }

        @Test
        @DisplayName("Deve retornar lista vazia de authorities quando nem scope nem roles existirem no JWT")
        void deveRetornarVazioQuandoSemClaimsDeAutoridade() {
            JwtAuthenticationConverter converter = securityConfig.jwtAuthenticationConverter();

            Jwt jwt = new Jwt(
                    "token-valor",
                    Instant.now(),
                    Instant.now().plusSeconds(3600),
                    Map.of("alg", "none"),
                    Map.of("sub", "user@petshop.com")
            );

            var auth = converter.convert(jwt);
            assertThat(auth).isNotNull();
            assertThat(auth.getAuthorities()).isEmpty();
        }
    }
}
