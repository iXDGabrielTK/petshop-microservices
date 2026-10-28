-- V5__indices_dashboard.sql
-- Índices de suporte para as queries do dashboard (ADR-0001 / DEBT-ARCH-001 Etapa E2)
-- Executado com executeInTransaction=false para permitir CREATE INDEX CONCURRENTLY no PostgreSQL

-- Predicado idêntico ao da query (StatusVenda.FATURADAS), senão o planner não usa o índice parcial (Q3)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_vendas_concluidas ON vendas (data_hora_conclusao)
    WHERE status IN ('CONCLUIDA', 'CANCELAMENTO_SOLICITADO');

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_itens_venda_venda ON itens_venda (venda_id, id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_produtos_abaixo_min ON produtos (id)
    WHERE estoque_disponivel <= estoque_minimo;
