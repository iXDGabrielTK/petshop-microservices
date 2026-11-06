/*
 * Copyright (c) 2026 Gabriel Tanaka (https://github.com/iXDGabrielTK)
 * SPDX-License-Identifier: AGPL-3.0-only
 * Consulte o arquivo LICENSE na raiz do projeto.
 */
package inv.inventory.api;

import java.util.List;
import java.util.Objects;

/**
 * Comando imutável de estoque vinculado a uma venda.
 * Lista vazia é permitida e resulta em no-op (paridade com o laço atual sobre uma coleção vazia).
 * Itens duplicados NÃO são consolidados (paridade — Q9).
 */
public record ComandoEstoqueVenda(long vendaId, List<ItemComando> itens) {
    public ComandoEstoqueVenda {
        if (vendaId <= 0) throw new IllegalArgumentException("vendaId deve ser positivo");
        Objects.requireNonNull(itens, "itens");
        itens = List.copyOf(itens);               // cópia defensiva imutável; rejeita elementos nulos
    }
}
