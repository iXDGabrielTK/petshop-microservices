/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.inventory.usecase;

import inv.inventory.infrastructure.web.dto.ProdutoRequest;
import inv.inventory.domain.model.Produto;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import common.exception.BusinessException;
import common.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
        if (request.codigoBarras() != null && produtoRepository.existsByCodigoBarras(request.codigoBarras())) {
            throw new BusinessException("Já existe um produto com este código de barras.");
        }

        Produto produto = new Produto();
        produto.setCodigoBarras(request.codigoBarras());
        produto.setNome(request.nome());
        produto.setUnidadeMedida(request.unidadeMedida());
        produto.setEstoqueDisponivel(request.quantidadeEstoque());
        produto.setPrecoVenda(request.precoVenda());

        return produtoRepository.save(produto);
    }

    public Page<Produto> listarTodos(Pageable pageable) {
        return produtoRepository.findAll(pageable);
    }

    public Page<Produto> buscarPorNome(String nome, Pageable pageable) {
        // A consulta derivada Containing aplica o escape de LIKE ao vincular o parâmetro.
        return produtoRepository.findByNomeContainingIgnoreCase(nome, pageable);
    }

    public Produto buscarPorEan(String ean) {
        return produtoRepository.findByCodigoBarras(ean)
                .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado com EAN: " + ean));
    }

    public Produto buscarPorId(Long id) {
        return produtoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado"));
    }
}
