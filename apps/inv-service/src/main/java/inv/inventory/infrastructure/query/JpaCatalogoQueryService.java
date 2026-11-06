/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.inventory.infrastructure.query;

import inv.inventory.api.CatalogoQueryService;
import inv.inventory.api.ProdutoCheckoutView;
import inv.inventory.infrastructure.persistence.ProdutoRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
@Transactional(readOnly = true)
class JpaCatalogoQueryService implements CatalogoQueryService {

    private final ProdutoRepository produtoRepository;

    JpaCatalogoQueryService(ProdutoRepository produtoRepository) {
        this.produtoRepository = Objects.requireNonNull(produtoRepository, "produtoRepository");
    }

    @Override
    public List<ProdutoCheckoutView> buscarParaCheckout(Set<Long> produtoIds) {
        Objects.requireNonNull(produtoIds, "produtoIds");
        if (produtoIds.isEmpty()) return List.of();
        return produtoRepository.buscarViewsCheckout(produtoIds);
    }
}
