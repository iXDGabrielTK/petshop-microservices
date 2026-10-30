package inv.checkout.infrastructure.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import common.exception.GlobalExceptionHandler;
import inv.checkout.domain.model.StatusPagamento;
import inv.checkout.infrastructure.web.dto.CriarIntencaoPagamentoRequest;
import inv.checkout.usecase.PagamentoService;
import inv.shared.domain.MetodoPagamento;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("PagamentoController — Testes Unitários com MockMvc")
class PagamentoControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private PagamentoService pagamentoService;

    @InjectMocks
    private PagamentoController pagamentoController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(pagamentoController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Nested
    @DisplayName("POST /pagamentos/webhook/confirmar/{pagamentoId}")
    class WebhookConfirmarTests {

        @Test
        @DisplayName("Deve confirmar pagamento com sucesso quando payload for válido")
        void deveConfirmarPagamentoComSucesso() throws Exception {
            String payloadValido = """
                    {
                        "valor_recebido": 150.50,
                        "nsu": "NSU-123456"
                    }
                    """;

            mockMvc.perform(post("/pagamentos/webhook/confirmar/42")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payloadValido))
                    .andExpect(status().isOk());

            verify(pagamentoService).confirmarPagamentoWebhook(42L, new BigDecimal("150.50"), "NSU-123456");
        }

        @Test
        @DisplayName("Deve retornar 400 Bad Request quando payload for vazio ({})")
        void deveRetornar400QuandoPayloadVazio() throws Exception {
            mockMvc.perform(post("/pagamentos/webhook/confirmar/42")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Dados Inválidos"))
                    .andExpect(jsonPath("$.fields.valor_recebido").value("valor_recebido é obrigatório"))
                    .andExpect(jsonPath("$.fields.nsu").value("nsu é obrigatório"));

            verifyNoInteractions(pagamentoService);
        }

        @Test
        @DisplayName("Deve retornar 400 Bad Request quando valor_recebido for nulo")
        void deveRetornar400QuandoValorRecebidoNulo() throws Exception {
            String json = """
                    {
                        "valor_recebido": null,
                        "nsu": "NSU-123456"
                    }
                    """;

            mockMvc.perform(post("/pagamentos/webhook/confirmar/42")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Dados Inválidos"))
                    .andExpect(jsonPath("$.fields.valor_recebido").value("valor_recebido é obrigatório"));

            verifyNoInteractions(pagamentoService);
        }

        @ParameterizedTest(name = "valor_recebido inválido: {0}")
        @ValueSource(strings = {"0.00", "-0.01", "-50.00"})
        @DisplayName("Deve retornar 400 Bad Request quando valor_recebido for menor ou igual a zero")
        void deveRetornar400QuandoValorRecebidoMenorOuIgualZero(String valorInvalido) throws Exception {
            String json = String.format("""
                    {
                        "valor_recebido": %s,
                        "nsu": "NSU-123456"
                    }
                    """, valorInvalido);

            mockMvc.perform(post("/pagamentos/webhook/confirmar/42")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Dados Inválidos"))
                    .andExpect(jsonPath("$.fields.valor_recebido").value("valor_recebido deve ser positivo"));

            verifyNoInteractions(pagamentoService);
        }

        @ParameterizedTest(name = "nsu inválido: \"{0}\"")
        @ValueSource(strings = {"", "   "})
        @DisplayName("Deve retornar 400 Bad Request quando nsu for em branco ou vazio")
        void deveRetornar400QuandoNsuEmBranco(String nsuInvalido) throws Exception {
            String json = String.format("""
                    {
                        "valor_recebido": 100.00,
                        "nsu": "%s"
                    }
                    """, nsuInvalido);

            mockMvc.perform(post("/pagamentos/webhook/confirmar/42")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Dados Inválidos"))
                    .andExpect(jsonPath("$.fields.nsu").value("nsu é obrigatório"));

            verifyNoInteractions(pagamentoService);
        }

        @Test
        @DisplayName("Deve retornar 400 Bad Request quando nsu for nulo")
        void deveRetornar400QuandoNsuNulo() throws Exception {
            String json = """
                    {
                        "valor_recebido": 100.00,
                        "nsu": null
                    }
                    """;

            mockMvc.perform(post("/pagamentos/webhook/confirmar/42")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Dados Inválidos"))
                    .andExpect(jsonPath("$.fields.nsu").value("nsu é obrigatório"));

            verifyNoInteractions(pagamentoService);
        }
    }

    @Nested
    @DisplayName("POST /pagamentos/webhook/falhar/{pagamentoId}")
    class WebhookFalharTests {

        @Test
        @DisplayName("Deve marcar pagamento como falha via webhook com sucesso")
        void deveFalharPagamentoComSucesso() throws Exception {
            mockMvc.perform(post("/pagamentos/webhook/falhar/42"))
                    .andExpect(status().isOk());

            verify(pagamentoService).falharPagamentoWebhook(42L);
        }
    }

    @Nested
    @DisplayName("GET /pagamentos/{id}/status")
    class ConsultarStatusTests {

        @Test
        @DisplayName("Deve retornar o status do pagamento com sucesso")
        void deveRetornarStatusDoPagamento() throws Exception {
            when(pagamentoService.buscarStatusPagamento(42L)).thenReturn(StatusPagamento.CONFIRMADO);

            mockMvc.perform(get("/pagamentos/42/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CONFIRMADO"));

            verify(pagamentoService).buscarStatusPagamento(42L);
        }
    }

    @Nested
    @DisplayName("POST /pagamentos/intencao/{vendaId}")
    class CriarIntencaoPagamentoTests {

        @Test
        @DisplayName("Deve criar intenção de pagamento com sucesso")
        void deveCriarIntencaoComSucesso() throws Exception {
            UUID uuid = UUID.randomUUID();
            CriarIntencaoPagamentoRequest request = new CriarIntencaoPagamentoRequest(
                    uuid,
                    MetodoPagamento.PIX,
                    new BigDecimal("99.90")
            );

            when(pagamentoService.criarIntencaoPagamento(eq(10L), eq(MetodoPagamento.PIX), any(BigDecimal.class), eq(uuid)))
                    .thenReturn(100L);

            mockMvc.perform(post("/pagamentos/intencao/10")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pagamentoId").value(100L));
        }

        @Test
        @DisplayName("Deve retornar 400 Bad Request ao criar intenção com payload inválido")
        void deveRetornar400CriarIntencaoPayloadInvalido() throws Exception {
            mockMvc.perform(post("/pagamentos/intencao/10")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Dados Inválidos"));
        }
    }
}
