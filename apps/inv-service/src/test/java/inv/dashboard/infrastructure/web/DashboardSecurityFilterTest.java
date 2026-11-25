/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.dashboard.infrastructure.web;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import common.exception.GlobalExceptionHandler;
import common.security.RsaKeyUtils;
import inv.dashboard.application.DashboardQueryService;
import inv.dashboard.application.DashboardSnapshot;
import inv.finance.api.FrescorProjecao;
import inv.shared.config.SecurityConfig;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(classes = DashboardSecurityFilterTest.TestContextConfig.class)
@WebAppConfiguration
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("DashboardSecurityFilterTest — Validação da Cadeia de Filtros do Spring Security em Dashboard")
class DashboardSecurityFilterTest {

    @TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({
            SecurityConfig.class,
            DashboardController.class,
            DashboardStatsMapper.class,
            GlobalExceptionHandler.class
    })
    static class TestContextConfig {
    }

    private static final KeyPair KEY_PAIR = RsaKeyUtils.generateKeyPair();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String pubKey = Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded());
        registry.add("jwt.public.key", () -> pubKey);
    }

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private DashboardQueryService queryService;

    private MockMvc mockMvc;

    @BeforeAll
    void setUpOnce() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter((Filter) context.getBean("springSecurityFilterChain"))
                .build();
    }

    @Test
    @DisplayName("GET /dashboard/stats deve permitir acesso para ROLE_ADMIN")
    void devePermitirAcessoParaAdministrador() throws Exception {
        DashboardSnapshot snapshot = new DashboardSnapshot(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0L,
                0L,
                0L,
                List.of(),
                List.of(),
                new DashboardSnapshot.Meta(Instant.parse("2026-10-15T12:00:00Z"), new FrescorProjecao(0L, 0L, 0L), List.of())
        );
        when(queryService.consultar()).thenReturn(snapshot);

        mockMvc.perform(get("/dashboard/stats")
                        .header("Authorization", "Bearer " + createValidToken("admin@petshop.com", List.of("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesCount").value(0));

        verify(queryService).consultar();
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "missing", "no-bearer", "malformed", "expired", "untrusted"})
    void deveBloquearAcessoSemCredenciaisAdministrativas(String credential) throws Exception {
        String authorization = switch (credential) {
            case "user" -> "Bearer " + createValidToken("user@petshop.com", List.of("ROLE_USER"));
            case "missing" -> null;
            case "no-bearer" -> createValidToken("admin@petshop.com", List.of("ROLE_ADMIN"));
            case "malformed" -> "Bearer token-invalido";
            case "expired" -> "Bearer " + createExpiredToken("admin@petshop.com", List.of("ROLE_ADMIN"));
            case "untrusted" -> "Bearer " + createTokenWithUntrustedKey("admin@petshop.com", List.of("ROLE_ADMIN"));
            default -> throw new IllegalArgumentException(credential);
        };

        MockHttpServletRequestBuilder request = get("/dashboard/stats");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }

        var result = mockMvc.perform(request);
        if (credential.equals("user")) {
            result.andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value("Acesso Negado"));
        } else {
            result.andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                            anyOf(equalTo("Bearer"), startsWith("Bearer "))))
                    .andExpect(content().string(""));
        }
        verifyNoInteractions(queryService);
    }

    private String createToken(String subject, List<String> roles, Date issueTime, Date expirationTime, KeyPair keyPair) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer("https://auth-service")
                .claim("roles", roles)
                .claim("scope", "read write")
                .issueTime(issueTime)
                .expirationTime(expirationTime)
                .build();

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).build(),
                claims
        );
        signedJWT.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
        return signedJWT.serialize();
    }

    private String createValidToken(String subject, List<String> roles) throws Exception {
        return createToken(subject, roles, new Date(), new Date(System.currentTimeMillis() + Duration.ofHours(1).toMillis()), KEY_PAIR);
    }

    private String createExpiredToken(String subject, List<String> roles) throws Exception {
        return createToken(subject, roles,
                new Date(System.currentTimeMillis() - Duration.ofHours(2).toMillis()),
                new Date(System.currentTimeMillis() - Duration.ofHours(1).toMillis()),
                KEY_PAIR);
    }

    private String createTokenWithUntrustedKey(String subject, List<String> roles) throws Exception {
        KeyPair untrustedKey = RsaKeyUtils.generateKeyPair();
        return createToken(subject, roles, new Date(), new Date(System.currentTimeMillis() + Duration.ofHours(1).toMillis()), untrustedKey);
    }
}
