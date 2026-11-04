package inv.checkout.infrastructure.web.controller;

import common.exception.GlobalExceptionHandler;
import common.exception.ResourceNotFoundException;
import inv.checkout.domain.model.StatusVenda;
import inv.checkout.infrastructure.web.VendaStatusRegistry;
import inv.checkout.infrastructure.web.dto.ItemReciboResponse;
import inv.checkout.infrastructure.web.dto.ReciboResponse;
import inv.checkout.infrastructure.web.dto.VendaStatusResponse;
import inv.checkout.usecase.CancelamentoVendaConflitoException;
import inv.checkout.usecase.CancelamentoVendaDesabilitadoException;
import inv.checkout.usecase.VendaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("VendaController — Testes Unitários com MockMvc")
class VendaControllerTest {

    private MockMvc mockMvc;

    @Mock
    private VendaService vendaService;

    @Mock
    private VendaStatusRegistry registry;

    @InjectMocks
    private VendaController vendaController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(vendaController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Nested
    @DisplayName("POST /vendas/{id}/cancelar")
    class CancelarVendaConcluidaTests {

        @Test
        @DisplayName("Deve retornar 200 OK com VendaStatusResponse ao cancelar venda concluída com sucesso")
        void deveRetornar200AoCancelarComSucesso() throws Exception {
            Long vendaId = 42L;
            VendaStatusResponse response = new VendaStatusResponse(
                    vendaId,
                    2L,
                    StatusVenda.CANCELADA,
                    new BigDecimal("100.00"),
                    new BigDecimal("120.00"),
                    new BigDecimal("20.00")
            );

            when(vendaService.cancelarVendaConcluida(vendaId)).thenReturn(response);

            mockMvc.perform(post("/vendas/{id}/cancelar", vendaId)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(42))
                    .andExpect(jsonPath("$.status").value("CANCELADA"))
                    .andExpect(jsonPath("$.valorTotal").value(100.00))
                    .andExpect(jsonPath("$.valorPago").value(120.00))
                    .andExpect(jsonPath("$.valorTroco").value(20.00));

            verify(vendaService).cancelarVendaConcluida(vendaId);
        }

        @Test
        @DisplayName("Deve retornar 409 Conflict com {\"mensagem\": \"...\"} quando status da venda for conflitante (precedência de handler local)")
        void deveRetornar409QuandoStatusForConflitante() throws Exception {
            Long vendaId = 42L;
            String msg = "Venda #42 não pode ser cancelada pois está no status: CANCELADA";
            when(vendaService.cancelarVendaConcluida(vendaId))
                    .thenThrow(new CancelamentoVendaConflitoException(msg));

            mockMvc.perform(post("/vendas/{id}/cancelar", vendaId)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.mensagem").value(msg));

            verify(vendaService).cancelarVendaConcluida(vendaId);
        }

        @Test
        @DisplayName("Deve retornar 409 Conflict defensivo sem lançar NPE quando exceção de conflito contiver mensagem nula")
        void deveRetornar409QuandoExcecaoConflitoTiverMensagemNula() throws Exception {
            Long vendaId = 42L;
            when(vendaService.cancelarVendaConcluida(vendaId))
                    .thenThrow(new CancelamentoVendaConflitoException(null));

            mockMvc.perform(post("/vendas/{id}/cancelar", vendaId)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.mensagem").isNotEmpty());

            verify(vendaService).cancelarVendaConcluida(vendaId);
        }

        @Test
        @DisplayName("Deve retornar 503 Service Unavailable com {\"mensagem\": \"...\"} quando feature flag estiver desabilitada (precedência de handler local)")
        void deveRetornar503QuandoFeatureFlagDesabilitada() throws Exception {
            Long vendaId = 42L;
            String msg = "Cancelamento de venda concluída temporariamente desabilitado";
            when(vendaService.cancelarVendaConcluida(vendaId))
                    .thenThrow(new CancelamentoVendaDesabilitadoException(msg));

            mockMvc.perform(post("/vendas/{id}/cancelar", vendaId)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.mensagem").value(msg));

            verify(vendaService).cancelarVendaConcluida(vendaId);
        }

        @Test
        @DisplayName("Deve retornar 503 Service Unavailable defensivo sem lançar NPE quando exceção desabilitada contiver mensagem nula")
        void deveRetornar503QuandoExcecaoDesabilitadaTiverMensagemNula() throws Exception {
            Long vendaId = 42L;
            when(vendaService.cancelarVendaConcluida(vendaId))
                    .thenThrow(new CancelamentoVendaDesabilitadoException(null));

            mockMvc.perform(post("/vendas/{id}/cancelar", vendaId)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.mensagem").isNotEmpty());

            verify(vendaService).cancelarVendaConcluida(vendaId);
        }

        @Test
        @DisplayName("Deve retornar 404 Not Found quando venda não for encontrada (tratado por GlobalExceptionHandler)")
        void deveRetornar404QuandoVendaNaoEncontrada() throws Exception {
            Long vendaId = 999L;
            String msg = "Venda não encontrada: 999";
            when(vendaService.cancelarVendaConcluida(vendaId))
                    .thenThrow(new ResourceNotFoundException(msg));

            mockMvc.perform(post("/vendas/{id}/cancelar", vendaId)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Recurso não encontrado"))
                    .andExpect(jsonPath("$.detail").value(msg));

            verify(vendaService).cancelarVendaConcluida(vendaId);
        }

        @Test
        @DisplayName("Deve retornar 400 Bad Request ao passar ID não-numérico no path")
        void deveFalharParaIdInvalido() throws Exception {
            mockMvc.perform(post("/vendas/invalido/cancelar")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Demais endpoints de VendaController (Regressão)")
    class EndpointsLegadosTests {

        @Test
        @DisplayName("GET /vendas/aberta deve retornar 200 quando existir venda ativa")
        void deveRetornar200QuandoExistirVendaAtiva() throws Exception {
            ReciboResponse recibo = new ReciboResponse(
                    "1",
                    "2026-11-03T10:00:00",
                    new BigDecimal("50.00"),
                    List.of(new ItemReciboResponse("Ração", new BigDecimal("1"), new BigDecimal("50.00"), new BigDecimal("50.00")))
            );
            when(vendaService.recuperarVendaAtiva()).thenReturn(recibo);

            mockMvc.perform(get("/vendas/aberta"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("1"))
                    .andExpect(jsonPath("$.total").value(50.00));
        }

        @Test
        @DisplayName("GET /vendas/aberta deve retornar 204 NoContent quando não houver venda ativa")
        void deveRetornar204QuandoNaoHouverVendaAtiva() throws Exception {
            when(vendaService.recuperarVendaAtiva()).thenReturn(null);

            mockMvc.perform(get("/vendas/aberta"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("GET /vendas/{id}/status deve retornar 200 com status atual quando wait=false")
        void deveRetornarStatusAtualQuandoWaitFalse() throws Exception {
            Long vendaId = 10L;
            VendaStatusResponse status = new VendaStatusResponse(
                    vendaId, 1L, StatusVenda.ABERTA, new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO
            );
            when(vendaService.buscarStatusVenda(vendaId)).thenReturn(status);

            MvcResult mvcResult = mockMvc.perform(get("/vendas/{id}/status", vendaId)
                            .param("wait", "false"))
                    .andExpect(request().asyncStarted())
                    .andReturn();

            mockMvc.perform(asyncDispatch(mvcResult))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(10))
                    .andExpect(jsonPath("$.status").value("ABERTA"));
        }
    }
}
