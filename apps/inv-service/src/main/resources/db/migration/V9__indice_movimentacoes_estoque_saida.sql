CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_movimentacoes_venda_saida
    ON movimentacoes_estoque (venda_id)
    WHERE tipo = 'SAIDA';
