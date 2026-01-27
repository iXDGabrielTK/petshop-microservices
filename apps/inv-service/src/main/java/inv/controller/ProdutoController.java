package inv.controller;

import inv.dto.ProdutoRequest;
import inv.dto.ProdutoResponse;
import inv.model.Produto;
import inv.service.ProdutoService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;
import org.springframework.security.access.prepost.PreAuthorize;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/produtos")
public class ProdutoController {

    private final ProdutoService produtoService;

    public ProdutoController(ProdutoService produtoService) {
        this.produtoService = produtoService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_pets:write') and hasRole('ADMIN')")
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

        return ResponseEntity
                .created(URI.create("/produtos/" + produto.getId()))
                .body(response);
    }



    @GetMapping
    public ResponseEntity<List<Produto>> listar(@RequestParam(required = false) String busca) {
        if (busca != null && !busca.isBlank()) {
            return ResponseEntity.ok(produtoService.buscarPorNome(busca));
        }
        return ResponseEntity.ok(produtoService.listarTodos());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Produto> buscarPorId(@PathVariable Long id) {
        return ResponseEntity.ok(produtoService.buscarPorId(id));
    }
}