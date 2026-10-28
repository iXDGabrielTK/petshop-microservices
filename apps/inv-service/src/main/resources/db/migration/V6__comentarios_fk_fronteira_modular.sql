COMMENT ON CONSTRAINT fk_itens_venda_produto ON itens_venda IS
  'FK de fronteira modular (Checkout -> Inventory). Sem mapeamento JPA (ItemVenda.produtoId). Remover apenas se os schemas forem separados fisicamente. ADR-0001 / DEBT-ARCH-002.';
COMMENT ON CONSTRAINT fk_movimentacoes_venda ON movimentacoes_estoque IS
  'FK de fronteira modular (Inventory -> Checkout). Sem mapeamento JPA (MovimentacaoEstoque.vendaId). Remover apenas se os schemas forem separados fisicamente. ADR-0001 / DEBT-ARCH-002.';
COMMENT ON CONSTRAINT fk_lancamento_venda ON lancamentos_financeiros IS
  'FK de fronteira modular (Finance -> Checkout). Remover apenas se os schemas forem separados fisicamente. ADR-0001 / DEBT-ARCH-002.';
