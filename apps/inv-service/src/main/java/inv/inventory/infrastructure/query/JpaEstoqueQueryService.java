package inv.inventory.infrastructure.query;

import inv.inventory.api.EstoqueQueryService;
import inv.inventory.api.ResumoEstoque;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Component
@Transactional(readOnly = true)
class JpaEstoqueQueryService implements EstoqueQueryService {

    private final ProdutoRepository produtoRepository;

    JpaEstoqueQueryService(ProdutoRepository produtoRepository) {
        this.produtoRepository = Objects.requireNonNull(produtoRepository, "produtoRepository");
    }

    @Override
    public ResumoEstoque resumo() {
        return new ResumoEstoque(produtoRepository.countProdutosComEstoqueBaixo());
    }
}
