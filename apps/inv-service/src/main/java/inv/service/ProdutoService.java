package inv.service;

import inv.dto.ProdutoRequest;
import inv.model.Produto;
import inv.repository.ProdutoRepository;
import common.exception.BusinessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProdutoService {

    private final ProdutoRepository produtoRepository;

    public ProdutoService(ProdutoRepository produtoRepository) {
        this.produtoRepository = produtoRepository;
    }

    @Transactional
    public Produto criarProduto(ProdutoRequest request) {
        if (request.codigoBarras() != null && !request.codigoBarras().isBlank()) {
            if (produtoRepository.existsByCodigoBarras(request.codigoBarras())) {
                throw new BusinessException("Já existe um produto com este código de barras.");
            }
        }

        Produto produto = new Produto();
        produto.setCodigoBarras(request.codigoBarras());
        produto.setNome(request.nome());
        produto.setUnidadeMedida(request.unidadeMedida());
        produto.setQuantidadeEstoque(request.quantidadeEstoque());
        produto.setPrecoVenda(request.precoVenda());

        return produtoRepository.save(produto);
    }

    public Page<Produto> listarTodos(Pageable pageable) {
        return produtoRepository.findAll(pageable);
    }

    public Page<Produto> buscarPorNome(String termo, Pageable pageable) {
        return produtoRepository.buscarPorNomeOuCodigo(termo, pageable);
    }

    public Produto buscarPorId(Long id) {
        return produtoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado"));
    }
}