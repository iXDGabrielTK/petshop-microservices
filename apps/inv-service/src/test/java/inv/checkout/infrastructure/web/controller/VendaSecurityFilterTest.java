/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.checkout.infrastructure.web.controller;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import common.exception.GlobalExceptionHandler;
import common.security.RsaKeyUtils;
import inv.checkout.domain.model.StatusVenda;
import inv.checkout.domain.model.Venda;
import inv.checkout.infrastructure.web.VendaStatusRegistry;
import inv.checkout.infrastructure.web.dto.VendaStatusResponse;
import inv.checkout.usecase.VendaService;
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
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(classes = VendaSecurityFilterTest.TestContextConfig.class)
@WebAppConfiguration
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("VendaSecurityFilterTest — Validação da Cadeia de Filtros do Spring Security em Venda")
class VendaSecurityFilterTest {

    @TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({
            SecurityConfig.class,
            VendaController.class,
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
    private VendaService service;

    @MockitoBean
    private VendaStatusRegistry registry;

    private MockMvc mockMvc;

    @BeforeAll
    void setUpOnce() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter((Filter) context.getBean("springSecurityFilterChain"))
                .build();
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


    private MockHttpServletRequestBuilder novaVenda() {
        return post("/vendas").contentType(MediaType.APPLICATION_JSON)
                .content("{\"itens\":[{\"produtoId\":1,\"quantidade\":1}]}");
    }

    private List<MockHttpServletRequestBuilder> protectedRequests() {
        return List.of(novaVenda(), get("/vendas/42/status"), get("/vendas/aberta"), post("/vendas/42/cancelar"));
    }

    private String adminAuthorization() throws Exception {
        return "Bearer " + createValidToken("admin@petshop.com", List.of("ROLE_ADMIN"));
    }

    @Test
    void devePermitirAberturaParaAdministrador() throws Exception {
        Venda venda = new Venda();
        ReflectionTestUtils.setField(venda, "id", 42L);
        when(service.iniciarVenda(any())).thenReturn(venda);
        mockMvc.perform(novaVenda().header("Authorization", adminAuthorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("42"));
        verify(service).iniciarVenda(any());
    }

    @Test
    void devePermitirStatusParaAdministrador() throws Exception {
        when(service.buscarStatusVenda(42L)).thenReturn(new VendaStatusResponse(
                42L, 1L, StatusVenda.ABERTA, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        var result = mockMvc.perform(get("/vendas/42/status").header("Authorization", adminAuthorization()))
                .andExpect(request().asyncStarted()).andReturn();
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ABERTA"));
        verify(service).buscarStatusVenda(42L);
        verifyNoInteractions(registry);
    }

    @Test
    void devePermitirConsultaDeVendaAbertaParaAdministrador() throws Exception {
        mockMvc.perform(get("/vendas/aberta").header("Authorization", adminAuthorization()))
                .andExpect(status().isNoContent());
        verify(service).recuperarVendaAtiva();
    }

    @Test
    void devePermitirCancelamentoParaAdministrador() throws Exception {
        when(service.cancelarVendaConcluida(42L)).thenReturn(new VendaStatusResponse(
                42L, 2L, StatusVenda.CANCELADA, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        mockMvc.perform(post("/vendas/42/cancelar").header("Authorization", adminAuthorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELADA"));
        verify(service).cancelarVendaConcluida(42L);
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
        for (MockHttpServletRequestBuilder request : protectedRequests()) {
            if (authorization != null) {
                request.header("Authorization", authorization);
            }
            var result = mockMvc.perform(request);
            if (credential.equals("user")) {
                result.andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.title").value("Acesso Negado"));
            } else {
                result.andExpect(status().isUnauthorized());
            }
        }
        verifyNoInteractions(service, registry);
    }
}
