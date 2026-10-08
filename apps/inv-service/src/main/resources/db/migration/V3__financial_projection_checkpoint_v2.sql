-- V3__financial_projection_checkpoint_v2.sql
-- Checkpoint corrigido para projeção de vendas, indexado por lancamento_id real (F1)
CREATE TABLE IF NOT EXISTS financial_projection_checkpoint_v2 (
    lancamento_id BIGINT PRIMARY KEY,
    processado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'OK'
);
