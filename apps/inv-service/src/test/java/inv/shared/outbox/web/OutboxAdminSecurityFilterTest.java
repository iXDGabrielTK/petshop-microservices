/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.shared.outbox.web;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import common.exception.ConflictException;
import common.exception.GlobalExceptionHandler;
import common.exception.ResourceNotFoundException;
import common.security.RsaKeyUtils;
import inv.shared.config.SecurityConfig;
import inv.shared.outbox.model.Outbox;
import inv.shared.outbox.service.OutboxReplayService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

@SpringJUnitConfig(classes = OutboxAdminSecurityFilterTest.TestContextConfig.class)
@WebAppConfiguration
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("OutboxAdminSecurityFilterTest — Validação de Segurança e Contratos HTTP Admin da Outbox")
class OutboxAdminSecurityFilterTest {

    @TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({
            SecurityConfig.class,
            OutboxAdminController.class,
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
    private OutboxReplayService outboxReplayService;

    private MockMvc mockMvc;

    @BeforeAll
    void setUpOnce() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter((Filter) context.getBean("springSecurityFilterChain"))
                .build();
    }

    @BeforeEach
    void resetMocks() {
        Mockito.reset(outboxReplayService);
    }

    @Test
    @DisplayName("DEVE limitar tamanho da página em GET /admin/outbox/quarantined ao teto de 100")
    void deveLimitarTamanhoPaginaAoTetoDe100() throws Exception {
        when(outboxReplayService.listQuarantined(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        mockMvc.perform(get("/admin/outbox/quarantined")
                        .param("page", "-2")
                        .param("size", "500")
                        .header("Authorization", adminAuthorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(outboxReplayService).listQuarantined(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("DEVE aplicar tamanho padrão 20 quando size <= 0 em GET /admin/outbox/quarantined")
    void deveUsarTamanhoPadraoQuandoSizeInvalido() throws Exception {
        when(outboxReplayService.listQuarantined(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/admin/outbox/quarantined")
                        .param("size", "0")
                        .header("Authorization", adminAuthorization()))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(outboxReplayService).listQuarantined(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("DEVE retornar detalhes e replay 200 OK para ROLE_ADMIN")
    void devePermitirInspecaoEReplayParaAdmin() throws Exception {
        LocalDateTime agora = LocalDateTime.of(2026, 11, 24, 14, 0, 0);
        OutboxRecordView view = new OutboxRecordView(
                42L, "pdv.exchange", "venda.pagamento.confirmado",
                "inv.checkout.infrastructure.messaging.PagamentoConfirmadoEvent",
                0, "{\"vendaId\":1,\"pagamentoId\":2}", Outbox.STATUS_QUARANTINED,
                5, agora.minusMinutes(10), null, agora.minusMinutes(1),
                "NO_ROUTE", "Mensagem sem rota", agora.minusMinutes(1), null, 0);
        when(outboxReplayService.findById(42L)).thenReturn(view);
        when(outboxReplayService.replay(42L))
                .thenReturn(new OutboxReplayResponse(42L, Outbox.STATUS_PENDING, agora, 1));

        mockMvc.perform(get("/admin/outbox/42").header("Authorization", adminAuthorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.status").value("QUARANTINED"))
                .andExpect(jsonPath("$.lastErrorType").value("NO_ROUTE"));

        mockMvc.perform(post("/admin/outbox/42/replay").header("Authorization", adminAuthorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.replayCount").value(1));
    }

    @Test
    @DisplayName("DEVE retornar ProblemDetail RFC 7807 (404 e 409) para ID inexistente ou conflito de estado")
    void deveRetornarProblemDetail404E409() throws Exception {
        when(outboxReplayService.findById(999L))
                .thenThrow(new ResourceNotFoundException("Registro da outbox não encontrado: 999"));
        when(outboxReplayService.replay(42L))
                .thenThrow(new ConflictException("Registro da outbox ID 42 não está em quarentena."));

        mockMvc.perform(get("/admin/outbox/999").header("Authorization", adminAuthorization()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Recurso não encontrado"));

        mockMvc.perform(post("/admin/outbox/42/replay").header("Authorization", adminAuthorization()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflito de Estado"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "missing", "no-bearer", "malformed", "expired", "untrusted"})
    @DisplayName("DEVE bloquear acesso aos endpoints /admin/outbox/** sem ROLE_ADMIN válida")
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

        List<MockHttpServletRequestBuilder> requests = List.of(
                get("/admin/outbox/quarantined"),
                get("/admin/outbox/42"),
                post("/admin/outbox/42/replay"));

        for (MockHttpServletRequestBuilder request : requests) {
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
        }
        verifyNoInteractions(outboxReplayService);
    }

    private String adminAuthorization() throws Exception {
        return "Bearer " + createValidToken("admin@petshop.com", List.of("ROLE_ADMIN"));
    }

    private String createToken(
            String subject, List<String> roles, Date issueTime, Date expirationTime, KeyPair keyPair) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer("https://auth-service")
                .claim("roles", roles)
                .claim("scope", "read write")
                .issueTime(issueTime)
                .expirationTime(expirationTime)
                .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims);
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
