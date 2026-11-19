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

import inv.inventory.usecase.ProdutoService;
import inv.inventory.domain.model.Produto;
import inv.inventory.domain.model.UnidadeMedida;
import java.math.BigDecimal;
import java.util.List;

class ProdutoControllerTest {
    private final ProdutoService service = mock(ProdutoService.class);
    private MockMvc mvc;
    private Produto produto;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProdutoController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        produto = new Produto(1L, "7891234567890", "Ração", BigDecimal.ONE, UnidadeMedida.UN,
                BigDecimal.TEN, BigDecimal.ONE, new BigDecimal("50.00"), 3L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/produtos/1", "/produtos/ean/7891234567890", "/produtos"})
    void expoeContratoExato(String url) throws Exception {
        when(service.buscarPorId(1L)).thenReturn(produto);
        when(service.buscarPorEan("7891234567890")).thenReturn(produto);
        when(service.listarTodos(any())).thenAnswer(i -> new PageImpl<>(List.of(produto), i.getArgument(0), 1));
        String root = url.equals("/produtos") ? "$.content[0]" : "$";
        mvc.perform(get(url)).andExpect(status().isOk())
                .andExpect(jsonPath(root + ".length()").value(6))
                .andExpect(jsonPath(root + ".id").value(1))
                .andExpect(jsonPath(root + ".codigoBarras").value("7891234567890"))
                .andExpect(jsonPath(root + ".nome").value("Ração"))
                .andExpect(jsonPath(root + ".unidadeMedida").value("UN"))
                .andExpect(jsonPath(root + ".estoqueDisponivel").value(10))
                .andExpect(jsonPath(root + ".precoVenda").value(50))
                .andExpect(jsonPath(root + ".estoqueMinimo").doesNotExist())
                .andExpect(jsonPath(root + ".estoqueReservado").doesNotExist())
                .andExpect(jsonPath(root + ".version").doesNotExist());
    }

    @ParameterizedTest
    @CsvSource({"-1,0,0,10", "-2,-5,0,10", "2,100,2,50", "1,5,1,5"})
    void normalizaEOrdenaTodasAsBuscas(int page, int size, int expectedPage, int expectedSize) throws Exception {
        Pageable pagina = PageRequest.of(expectedPage, expectedSize, Sort.by("id"));
        when(service.listarTodos(pagina)).thenReturn(Page.empty(pagina));
        when(service.buscarPorNome("Ração", pagina)).thenReturn(Page.empty(pagina));
        for (String filtro : List.of("", "nome", "busca")) {
            var request = get("/produtos").param("page", "" + page).param("size", "" + size)
                    .param("sort", "nome,desc");
            if (!filtro.isEmpty()) request.param(filtro, "Ração");
            mvc.perform(request).andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(expectedSize))
                    .andExpect(jsonPath("$.number").value(expectedPage));
        }
        verify(service).listarTodos(pagina);
        verify(service, times(2)).buscarPorNome("Ração", pagina);
    }
}
