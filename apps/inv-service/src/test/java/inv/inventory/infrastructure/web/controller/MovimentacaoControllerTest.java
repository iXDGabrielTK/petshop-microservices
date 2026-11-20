/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.inventory.infrastructure.web.controller;

import common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import inv.inventory.infrastructure.persistence.MovimentacaoRepository;
import inv.inventory.infrastructure.web.dto.MovimentacaoResponse;
import inv.inventory.domain.model.TipoMovimentacao;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

class MovimentacaoControllerTest {
    private final MovimentacaoRepository repository = mock(MovimentacaoRepository.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new MovimentacaoController(repository))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalido", "produto.nome,asc", "id,asc,ignorecase",
            "dataHora,asc,ignorecase", "tipo,asc,ignorecase", "quantidade,asc,ignorecase"})
    void rejeitaOrdenacaoAntesDaConsulta(String sort) throws Exception {
        mvc.perform(get("/movimentacoes").param("sort", sort))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Regra de Negócio Violada"));
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "dataHora", "tipo", "quantidade"})
    void aceitaWhitelistEDesempata(String campo) throws Exception {
        Sort esperado = Sort.by(campo).descending();
        if (!campo.equals("id")) esperado = esperado.and(Sort.by("id"));
        Pageable pagina = PageRequest.of(0, 20, esperado);
        when(repository.listarResumo(pagina)).thenReturn(Page.empty(pagina));
        mvc.perform(get("/movimentacoes").param("sort", campo + ",desc"))
                .andExpect(status().isOk());
        verify(repository).listarResumo(pagina);
    }

    @ParameterizedTest
    @CsvSource({"-1,0,0,20", "-2,-5,0,20", "2,100,2,50", "1,5,1,5"})
    void normalizaPagina(int page, int size, int expectedPage, int expectedSize) throws Exception {
        Pageable pagina = PageRequest.of(expectedPage, expectedSize,
                Sort.by(Sort.Order.desc("dataHora"), Sort.Order.asc("id")));
        when(repository.listarResumo(pagina)).thenReturn(Page.empty(pagina));
        mvc.perform(get("/movimentacoes").param("page", "" + page).param("size", "" + size))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(expectedSize))
                .andExpect(jsonPath("$.number").value(expectedPage));
        verify(repository).listarResumo(pagina);
    }

    @Test
    void serializaSomenteContratoPlano() throws Exception {
        var dto = new MovimentacaoResponse(1L, 2L, "Ração", "7891234567890", 3L,
                TipoMovimentacao.SAIDA, BigDecimal.ONE, LocalDateTime.of(2026, 11, 16, 10, 0), "Venda");
        when(repository.listarResumo(any())).thenAnswer(i -> new PageImpl<>(List.of(dto), i.getArgument(0), 1));
        mvc.perform(get("/movimentacoes"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].length()").value(9))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].produtoId").value(2))
                .andExpect(jsonPath("$.content[0].produtoNome").value("Ração"))
                .andExpect(jsonPath("$.content[0].codigoBarras").value("7891234567890"))
                .andExpect(jsonPath("$.content[0].vendaId").value(3))
                .andExpect(jsonPath("$.content[0].tipo").value("SAIDA"))
                .andExpect(jsonPath("$.content[0].quantidade").value(1))
                .andExpect(jsonPath("$.content[0].dataHora").value("2026-11-16T10:00:00"))
                .andExpect(jsonPath("$.content[0].observacao").value("Venda"))
                .andExpect(jsonPath("$.content[0].produto").doesNotExist());
    }
}
