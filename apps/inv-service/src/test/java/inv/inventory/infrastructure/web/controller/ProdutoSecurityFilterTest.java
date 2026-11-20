/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.inventory.infrastructure.web.controller;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import common.exception.GlobalExceptionHandler;
import common.security.RsaKeyUtils;
import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.UnidadeMedida;
import inv.inventory.usecase.ProdutoService;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(classes = ProdutoSecurityFilterTest.TestContextConfig.class)
@WebAppConfiguration
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ProdutoSecurityFilterTest — Validação da Cadeia de Filtros do Spring Security em Produto")
class ProdutoSecurityFilterTest {

    @TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({
            SecurityConfig.class,
            ProdutoController.class,
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
    private ProdutoService service;

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


    private MockHttpServletRequestBuilder novoProduto() {
        return post("/produtos").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"Racao\",\"unidadeMedida\":\"UN\",\"quantidadeEstoque\":10,\"precoVenda\":25}");
    }

    private List<MockHttpServletRequestBuilder> protectedRequests() {
        return List.of(
                novoProduto(),
                get("/produtos"),
                get("/produtos/42"),
                get("/produtos/ean/7891234567890")
        );
    }

    private Produto produtoValido() {
        return new Produto(
                42L,
                "7891234567890",
                "Racao",
                BigDecimal.ONE,
                UnidadeMedida.UN,
                BigDecimal.TEN,
                BigDecimal.ZERO,
                new BigDecimal("25.00"),
                1L
        );
    }

    private String userAuthorization() throws Exception {
        return "Bearer " + createValidToken("user@petshop.com", List.of("ROLE_USER"));
    }

    @Test
    void devePermitirCadastroParaAdministrador() throws Exception {
        Produto produto = new Produto();
        produto.setId(42L);
        produto.setNome("Racao");
        when(service.criarProduto(any())).thenReturn(produto);
        String token = createValidToken("admin@petshop.com", List.of("ROLE_ADMIN"));
        mockMvc.perform(novoProduto().header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(42));
        verify(service).criarProduto(any());
    }

    @Test
    void devePermitirListagemParaUsuarioComum() throws Exception {
        Produto produto = produtoValido();
        when(service.listarTodos(any()))
                .thenAnswer(inv -> new PageImpl<>(List.of(produto), inv.getArgument(0), 1));

        mockMvc.perform(get("/produtos").header("Authorization", userAuthorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(42))
                .andExpect(jsonPath("$.content[0].codigoBarras").value("7891234567890"))
                .andExpect(jsonPath("$.content[0].nome").value("Racao"));

        verify(service).listarTodos(any());
    }

    @Test
    void devePermitirBuscaPorIdParaUsuarioComum() throws Exception {
        when(service.buscarPorId(42L)).thenReturn(produtoValido());

        mockMvc.perform(get("/produtos/42").header("Authorization", userAuthorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.codigoBarras").value("7891234567890"))
                .andExpect(jsonPath("$.nome").value("Racao"));

        verify(service).buscarPorId(42L);
    }

    @Test
    void devePermitirBuscaPorEanParaUsuarioComum() throws Exception {
        when(service.buscarPorEan("7891234567890")).thenReturn(produtoValido());

        mockMvc.perform(get("/produtos/ean/7891234567890").header("Authorization", userAuthorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.codigoBarras").value("7891234567890"))
                .andExpect(jsonPath("$.nome").value("Racao"));

        verify(service).buscarPorEan("7891234567890");
    }

    @Test
    void deveBloquearCadastroParaUsuarioComum() throws Exception {
        mockMvc.perform(novoProduto().header("Authorization", userAuthorization()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Acesso Negado"));

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "no-bearer", "malformed", "expired", "untrusted"})
    void deveBloquearAcessoSemCredencialValida(String credential) throws Exception {
        String authorization = switch (credential) {
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
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                            anyOf(equalTo("Bearer"), startsWith("Bearer "))))
                    .andExpect(content().string(""));
        }
        verifyNoInteractions(service);
    }
}
