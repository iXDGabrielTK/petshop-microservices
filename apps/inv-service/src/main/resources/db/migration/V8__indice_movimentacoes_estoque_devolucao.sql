-- V8__indice_movimentacoes_estoque_devolucao.sql
-- Requer sidecar V8__indice_movimentacoes_estoque_devolucao.sql.conf com: executeInTransaction=false
-- Índice único parcial para garantir idempotência física em devoluções de estoque (DEBT-ARCH-003 / E2 - Q5, Q8)

CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uk_movimentacoes_venda_produto_devolucao
    ON movimentacoes_estoque (venda_id, produto_id)
    WHERE tipo = 'DEVOLUCAO';
