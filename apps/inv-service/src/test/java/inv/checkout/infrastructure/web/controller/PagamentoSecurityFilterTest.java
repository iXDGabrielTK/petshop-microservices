package inv.checkout.infrastructure.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import common.exception.GlobalExceptionHandler;
import common.security.RsaKeyUtils;
import inv.checkout.domain.model.StatusPagamento;
import inv.checkout.infrastructure.web.dto.ConfirmarPagamentoWebhookRequest;
import inv.checkout.infrastructure.web.dto.CriarIntencaoPagamentoRequest;
import inv.checkout.usecase.PagamentoService;
import inv.shared.config.SecurityConfig;
import inv.shared.domain.MetodoPagamento;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import org.springframework.test.web.servlet.MockMvc;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitConfig(classes = PagamentoSecurityFilterTest.TestContextConfig.class)
@WebAppConfiguration
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("PagamentoSecurityFilterTest — Validação da Cadeia de Filtros do Spring Security em Pagamentos")
class PagamentoSecurityFilterTest {

    @TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({
            SecurityConfig.class,
            PagamentoController.class,
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

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private PagamentoService pagamentoService;

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

    @Nested
    @DisplayName("1. Endpoints Públicos (Webhooks)")
    class EndpointsPublicosTests {

        @Test
        @DisplayName("POST /pagamentos/webhook/confirmar/{id} deve passar com sucesso SEM token de autorização")
        void devePermitirWebhookConfirmarSemToken() throws Exception {
            ConfirmarPagamentoWebhookRequest request = new ConfirmarPagamentoWebhookRequest(
                    new BigDecimal("150.50"),
                    "NSU-123456"
            );

            mockMvc.perform(post("/pagamentos/webhook/confirmar/42")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());

            verify(pagamentoService).confirmarPagamentoWebhook(42L, new BigDecimal("150.50"), "NSU-123456");
        }

        @Test
        @DisplayName("POST /pagamentos/webhook/falhar/{id} deve passar com sucesso SEM token de autorização")
        void devePermitirWebhookFalharSemToken() throws Exception {
            mockMvc.perform(post("/pagamentos/webhook/falhar/42"))
                    .andExpect(status().isOk());

            verify(pagamentoService).falharPagamentoWebhook(42L);
        }
    }

    @Nested
    @DisplayName("2. Rejeição de Acesso Não Autenticado (401 Unauthorized)")
    class RejeicaoSemAutenticacaoTests {

        @Test
        @DisplayName("POST /pagamentos/intencao/{vendaId} deve retornar 401 Unauthorized quando chamado sem token")
        void deveRejeitarCriarIntencaoSemToken() throws Exception {
            CriarIntencaoPagamentoRequest request = new CriarIntencaoPagamentoRequest(
                    UUID.randomUUID(),
                    MetodoPagamento.PIX,
                    new BigDecimal("100.00")
            );

            mockMvc.perform(post("/pagamentos/intencao/10")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(pagamentoService);
        }

        @Test
        @DisplayName("GET /pagamentos/{id}/status deve retornar 401 Unauthorized quando chamado sem token")
        void deveRejeitarConsultarStatusSemToken() throws Exception {
            mockMvc.perform(get("/pagamentos/42/status"))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(pagamentoService);
        }

        @Test
        @DisplayName("GET /pagamentos/{id}/status deve retornar 401 Unauthorized quando header Authorization não tiver prefixo Bearer")
        void deveRejeitarHeaderSemPrefixoBearer() throws Exception {
            String token = createValidToken("user@petshop.com", List.of("ROLE_USER"));
            mockMvc.perform(get("/pagamentos/42/status")
                            .header("Authorization", token))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(pagamentoService);
        }
    }

    @Nested
    @DisplayName("3. Validação Criptográfica e Integridade do JWT (401 Unauthorized)")
    class ValidacaoJwtTests {

        @Test
        @DisplayName("GET /pagamentos/{id}/status deve retornar 401 Unauthorized com token malformado")
        void deveRejeitarConsultarStatusComTokenInvalido() throws Exception {
            mockMvc.perform(get("/pagamentos/42/status")
                            .header("Authorization", "Bearer token-invalido-malformado"))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(pagamentoService);
        }

        @Test
        @DisplayName("GET /pagamentos/{id}/status deve retornar 401 Unauthorized com token expirado")
        void deveRejeitarConsultarStatusComTokenExpirado() throws Exception {
            String token = createExpiredToken("user@petshop.com", List.of("ROLE_USER"));
            mockMvc.perform(get("/pagamentos/42/status")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(pagamentoService);
        }

        @Test
        @DisplayName("GET /pagamentos/{id}/status deve retornar 401 Unauthorized com token assinado por chave privada não confiável")
        void deveRejeitarTokenComChaveNaoConfiavel() throws Exception {
            String token = createTokenWithUntrustedKey("user@petshop.com", List.of("ROLE_USER"));
            mockMvc.perform(get("/pagamentos/42/status")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(pagamentoService);
        }
    }

    @Nested
    @DisplayName("4. Acesso Autorizado com Token Válido (200 OK)")
    class AcessoAutorizadoTests {

        @Test
        @DisplayName("POST /pagamentos/intencao/{vendaId} deve responder 200 OK quando fornecido JWT válido")
        void devePermitirCriarIntencaoComTokenValido() throws Exception {
            String token = createValidToken("user@petshop.com", List.of("ROLE_USER"));
            UUID uuid = UUID.randomUUID();
            CriarIntencaoPagamentoRequest request = new CriarIntencaoPagamentoRequest(
                    uuid,
                    MetodoPagamento.PIX,
                    new BigDecimal("100.00")
            );

            when(pagamentoService.criarIntencaoPagamento(eq(10L), eq(MetodoPagamento.PIX), any(BigDecimal.class), eq(uuid)))
                    .thenReturn(999L);

            mockMvc.perform(post("/pagamentos/intencao/10")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pagamentoId").value(999L));

            verify(pagamentoService).criarIntencaoPagamento(eq(10L), eq(MetodoPagamento.PIX), any(BigDecimal.class), eq(uuid));
        }

        @Test
        @DisplayName("GET /pagamentos/{id}/status deve responder 200 OK quando fornecido JWT válido")
        void devePermitirConsultarStatusComTokenValido() throws Exception {
            String token = createValidToken("user@petshop.com", List.of("ROLE_USER"));

            when(pagamentoService.buscarStatusPagamento(42L)).thenReturn(StatusPagamento.CONFIRMADO);

            mockMvc.perform(get("/pagamentos/42/status")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CONFIRMADO"));

            verify(pagamentoService).buscarStatusPagamento(42L);
        }
    }
}
