/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
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
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
    @DisplayName("Deve retornar 409 ProblemDetail quando status da venda for conflitante")
    void deveRetornar409QuandoStatusForConflitante() throws Exception {
        Long vendaId = 42L;
        String msg = "Venda #42 não pode ser cancelada pois está no status: CANCELADA";
        String causaInterna = "causa-interna-conflito-nao-expor-409";
        when(vendaService.cancelarVendaConcluida(vendaId))
                .thenThrow(new CancelamentoVendaConflitoException(msg, new IllegalStateException(causaInterna)));

        MvcResult result = mockMvc.perform(post("/vendas/{id}/cancelar", vendaId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflito de Estado"))
                .andExpect(jsonPath("$.type").value("https://petshop.com/errors/conflict"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.detail").value(msg))
                .andExpect(jsonPath("$.mensagem").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.cause").doesNotHaveJsonPath())
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain(causaInterna);

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
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflito de Estado"))
                .andExpect(jsonPath("$.type").value("https://petshop.com/errors/conflict"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.detail").value("Conflito ao cancelar venda: status atual incompatível"))
                .andExpect(jsonPath("$.mensagem").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.cause").doesNotHaveJsonPath());

        verify(vendaService).cancelarVendaConcluida(vendaId);
    }

    @Test
    @DisplayName("Deve retornar 503 ProblemDetail quando feature flag estiver desabilitada")
    void deveRetornar503QuandoFeatureFlagDesabilitada() throws Exception {
        Long vendaId = 42L;
        String msg = "Cancelamento de venda concluída temporariamente desabilitado";
        String causaInterna = "causa-interna-indisponibilidade-nao-expor-503";
        when(vendaService.cancelarVendaConcluida(vendaId))
                .thenThrow(new CancelamentoVendaDesabilitadoException(msg, new IllegalStateException(causaInterna)));

        MvcResult result = mockMvc.perform(post("/vendas/{id}/cancelar", vendaId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.title").value("Serviço Indisponível"))
                .andExpect(jsonPath("$.type").value("https://petshop.com/errors/service-unavailable"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.detail").value(msg))
                .andExpect(jsonPath("$.mensagem").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.cause").doesNotHaveJsonPath())
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain(causaInterna);

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
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.title").value("Serviço Indisponível"))
                .andExpect(jsonPath("$.type").value("https://petshop.com/errors/service-unavailable"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.detail").value("Cancelamento de venda concluída temporariamente desabilitado"))
                .andExpect(jsonPath("$.mensagem").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.cause").doesNotHaveJsonPath());

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

    @Test
    void conflitoPreservaMensagemECausa() {
        String mensagemPadrao = "Conflito ao cancelar venda: status atual incompatível";
        String mensagem = "Venda já cancelada";
        Throwable causa = new IllegalStateException("Falha interna");

        assertThat(new CancelamentoVendaConflitoException()).hasMessage(mensagemPadrao).hasNoCause();
        assertThat(new CancelamentoVendaConflitoException(mensagem)).hasMessage(mensagem).hasNoCause();
        assertThat(new CancelamentoVendaConflitoException(null)).hasMessage(mensagemPadrao).hasNoCause();
        assertThat(new CancelamentoVendaConflitoException(mensagem, causa))
                .hasMessage(mensagem)
                .hasCauseReference(causa);
        assertThat(new CancelamentoVendaConflitoException(null, causa))
                .hasMessage(mensagemPadrao)
                .hasCauseReference(causa);
    }

    @Test
    void indisponibilidadePreservaMensagemECausa() {
        String mensagemPadrao = "Cancelamento de venda concluída temporariamente desabilitado";
        String mensagem = "Cancelamentos suspensos";
        Throwable causa = new IllegalStateException("Falha interna");

        assertThat(new CancelamentoVendaDesabilitadoException()).hasMessage(mensagemPadrao).hasNoCause();
        assertThat(new CancelamentoVendaDesabilitadoException(mensagem)).hasMessage(mensagem).hasNoCause();
        assertThat(new CancelamentoVendaDesabilitadoException(null)).hasMessage(mensagemPadrao).hasNoCause();
        assertThat(new CancelamentoVendaDesabilitadoException(mensagem, causa))
                .hasMessage(mensagem)
                .hasCauseReference(causa);
        assertThat(new CancelamentoVendaDesabilitadoException(null, causa))
                .hasMessage(mensagemPadrao)
                .hasCauseReference(causa);
    }

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
