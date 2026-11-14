/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.inventory.api;

import java.util.List;
import java.util.Set;

/** Consulta de catálogo para o PDV. Nunca expõe a entidade Produto. */
public interface CatalogoQueryService {
    /**
     * Retorna as views dos produtos existentes entre os IDs informados.
     * IDs inexistentes são omitidos (o chamador decide a semântica de "não encontrado").
     * NÃO filtra produto inativo (paridade — Q2/Q9).
     */
    List<ProdutoCheckoutView> buscarParaCheckout(Set<Long> produtoIds);
}
