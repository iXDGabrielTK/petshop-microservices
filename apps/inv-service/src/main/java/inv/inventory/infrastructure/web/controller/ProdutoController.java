/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.inventory.infrastructure.web.controller;

import inv.inventory.infrastructure.web.dto.ProdutoRequest;
import inv.inventory.infrastructure.web.dto.ProdutoResumoResponse;
import org.springframework.data.domain.Sort;
import inv.inventory.infrastructure.web.dto.ProdutoResponse;
import inv.inventory.domain.model.Produto;
import inv.inventory.usecase.ProdutoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/produtos")
public class ProdutoController {

    private final ProdutoService produtoService;

    public ProdutoController(ProdutoService produtoService) {
        this.produtoService = produtoService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProdutoResponse> criar(
            @RequestBody @Valid ProdutoRequest request) {

        Produto produto = produtoService.criarProduto(request);

        ProdutoResponse response = new ProdutoResponse(
                produto.getId(),
                produto.getCodigoBarras(),
                produto.getNome(),
                produto.getUnidadeMedida(),
                produto.getEstoqueDisponivel(),
                produto.getPrecoVenda()
        );

       return ResponseEntity.ok(response);
    }

    @GetMapping("/ean/{ean}")
    public ResponseEntity<ProdutoResumoResponse> buscarPorEan(
            @PathVariable
            @Pattern(regexp = "\\d{8,14}", message = "O EAN deve conter entre 8 e 14 dígitos numéricos")
            String ean
    ) {
        return ResponseEntity.ok(ProdutoResumoResponse.from(produtoService.buscarPorEan(ean)));
    }

    @GetMapping
    public ResponseEntity<Page<ProdutoResumoResponse>> listar(
            @RequestParam(required = false) String nome,
            @RequestParam(required = false) String busca,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        int tamanhoSeguro = size < 1 ? 10 : Math.min(size, 50);
        Pageable pageable = PageRequest.of(Math.max(page, 0), tamanhoSeguro, Sort.by("id").ascending());

        String termoBusca = (nome != null && !nome.isBlank()) ? nome : busca;

        if (termoBusca != null && !termoBusca.isBlank()) {
            return ResponseEntity.ok(produtoService.buscarPorNome(termoBusca, pageable).map(ProdutoResumoResponse::from));
        }

        return ResponseEntity.ok(produtoService.listarTodos(pageable).map(ProdutoResumoResponse::from));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProdutoResumoResponse> buscarPorId(@PathVariable Long id) {
        return ResponseEntity.ok(ProdutoResumoResponse.from(produtoService.buscarPorId(id)));
    }
}
