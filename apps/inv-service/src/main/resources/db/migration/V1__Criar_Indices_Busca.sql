-- V1__Criar_Indices_Busca.sql

-- Índice para busca rápida de código de barras
CREATE INDEX IF NOT EXISTS idx_produto_codigo_barras ON produto (codigo_barras);

-- Índice para busca case-insensitive no nome
CREATE INDEX IF NOT EXISTS idx_produto_nome_lower ON produto (lower(nome));