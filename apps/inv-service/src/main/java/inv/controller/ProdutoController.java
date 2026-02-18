package inv.controller;

import inv.dto.ProdutoRequest;
import inv.dto.ProdutoResponse;
import inv.model.Produto;
import inv.service.ProdutoService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;
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
                HtmlUtils.htmlEscape(produto.getNome()),
                produto.getUnidadeMedida(),
                produto.getQuantidadeEstoque(),
                produto.getPrecoVenda()
        );

       return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<Page<Produto>> listar(
            @RequestParam(required = false) String busca,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        int tamanhoSeguro = Math.min(size, 50);

        Pageable pageable = PageRequest.of(page, tamanhoSeguro);

        if (busca != null && !busca.isBlank()) {
            return ResponseEntity.ok(produtoService.buscarPorNome(busca, pageable));
        }
        return ResponseEntity.ok(produtoService.listarTodos(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Produto> buscarPorId(@PathVariable Long id) {
        return ResponseEntity.ok(produtoService.buscarPorId(id));
    }
}