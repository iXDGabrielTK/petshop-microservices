package inv.controller;

import inv.dto.ProdutoRequest;
import inv.dto.ProdutoResponse;
import inv.model.Produto;
import inv.service.ProdutoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
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

    @GetMapping("/ean/{ean}")
    public ResponseEntity<Produto> buscarPorEan(
            @PathVariable
            @Pattern(regexp = "\\d{8,14}", message = "O EAN deve conter entre 8 e 14 dígitos numéricos")
            String ean
    ) {
        return ResponseEntity.ok(produtoService.buscarPorEan(ean));
    }

    @GetMapping
    public ResponseEntity<Page<Produto>> listar(
            @RequestParam(required = false) String nome,
            @RequestParam(required = false) String busca,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        int tamanhoSeguro = Math.min(size, 50);
        Pageable pageable = PageRequest.of(page, tamanhoSeguro);

        String termoBusca = (nome != null && !nome.isBlank()) ? nome : busca;

        if (termoBusca != null && !termoBusca.isBlank()) {
            return ResponseEntity.ok(produtoService.buscarPorNome(termoBusca, pageable));
        }

        return ResponseEntity.ok(produtoService.listarTodos(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Produto> buscarPorId(@PathVariable Long id) {
        return ResponseEntity.ok(produtoService.buscarPorId(id));
    }
}