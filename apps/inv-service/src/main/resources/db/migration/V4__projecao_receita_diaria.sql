-- V4__projecao_receita_diaria.sql (expand-only, aditiva)
-- Projeção CQRS de grão diário para receitas e despesas no regime de caixa (ADR-0006 estendida / DEBT-ARCH-001 E3)

CREATE TABLE IF NOT EXISTS financial_projection_diaria (
    data_referencia     DATE PRIMARY KEY,
    total_creditos      NUMERIC(14,2) NOT NULL DEFAULT 0,
    total_debitos       NUMERIC(14,2) NOT NULL DEFAULT 0,
    qtd_lancamentos     BIGINT        NOT NULL DEFAULT 0,
    ultima_atualizacao  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Checkpoint próprio: torna a projeção diária idempotente por si só (listener E backfill)
CREATE TABLE IF NOT EXISTS financial_projection_diaria_checkpoint (
    lancamento_id BIGINT PRIMARY KEY,
    processado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
