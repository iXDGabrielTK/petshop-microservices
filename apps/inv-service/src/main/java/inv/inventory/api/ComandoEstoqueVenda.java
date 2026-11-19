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
 * Preserva a lista recebida, inclusive itens repetidos. Na devolução, o Inventory
 * consolida os itens por produto e aceita a lista vazia somente para vendas sem saídas.
 */
public record ComandoEstoqueVenda(long vendaId, List<ItemComando> itens) {
    public ComandoEstoqueVenda {
        if (vendaId <= 0) throw new IllegalArgumentException("vendaId deve ser positivo");
        Objects.requireNonNull(itens, "itens");
        itens = List.copyOf(itens);               // cópia defensiva imutável; rejeita elementos nulos
    }
}
