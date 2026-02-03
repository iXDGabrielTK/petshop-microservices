package inv.controller;

import inv.model.MovimentacaoEstoque;
import inv.repository.MovimentacaoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/movimentacoes")
public class MovimentacaoController {

    private final MovimentacaoRepository repository;

    public MovimentacaoController(MovimentacaoRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ResponseEntity<Page<MovimentacaoEstoque>> listar(
            @PageableDefault(size = 20, sort = "dataHora", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(repository.findAll(pageable));
    }
}