-- V1__Schema_Inicial.sql

-- 1. Tabela PRODUTOS
CREATE TABLE produtos (
                          id BIGSERIAL PRIMARY KEY,
                          codigo_barras VARCHAR(255) UNIQUE,
                          nome VARCHAR(255) NOT NULL,
                          estoque_minimo NUMERIC(10, 3),
                          unidade_medida VARCHAR(50) NOT NULL,
                          quantidade_estoque NUMERIC(10, 3) NOT NULL,
                          preco_venda NUMERIC(10, 2) NOT NULL,
                          version BIGINT
);

-- 2. Tabela VENDAS
CREATE TABLE vendas (
                        id BIGSERIAL PRIMARY KEY,
                        data_hora TIMESTAMP NOT NULL,
                        valor_total NUMERIC(10, 2) NOT NULL
);

-- 3. Tabela ITENS_VENDA
CREATE TABLE itens_venda (
                             id BIGSERIAL PRIMARY KEY,
                             venda_id BIGINT NOT NULL,
                             produto_id BIGINT NOT NULL,
                             nome_produto_snapshot VARCHAR(255) NOT NULL,
                             preco_unitario_snapshot NUMERIC(10, 2) NOT NULL,
                             quantidade NUMERIC(10, 3) NOT NULL,
                             CONSTRAINT fk_itens_venda_venda FOREIGN KEY (venda_id) REFERENCES vendas(id),
                             CONSTRAINT fk_itens_venda_produto FOREIGN KEY (produto_id) REFERENCES produtos(id)
);

-- 4. Tabela MOVIMENTACOES_ESTOQUE
CREATE TABLE movimentacoes_estoque (
                                       id BIGSERIAL PRIMARY KEY,
                                       produto_id BIGINT NOT NULL,
                                       venda_id BIGINT,
                                       tipo VARCHAR(50) NOT NULL,
                                       quantidade NUMERIC(10, 3) NOT NULL,
                                       data_hora TIMESTAMP NOT NULL,
                                       observacao VARCHAR(255),
                                       CONSTRAINT fk_movimentacoes_produto FOREIGN KEY (produto_id) REFERENCES produtos(id),
                                       CONSTRAINT fk_movimentacoes_venda FOREIGN KEY (venda_id) REFERENCES vendas(id)
);

-- 5. Tabela OUTBOX
CREATE TABLE outbox (
                        id BIGSERIAL PRIMARY KEY,
                        exchange VARCHAR(255) NOT NULL,
                        routing_key VARCHAR(255) NOT NULL,
                        payload TEXT NOT NULL,
                        event_type VARCHAR(255) NOT NULL,
                        version INTEGER NOT NULL DEFAULT 1,
                        created_at TIMESTAMP NOT NULL
);

-- 6. Índices de Performance (Aqueles que você queria)
CREATE INDEX IF NOT EXISTS idx_produto_codigo_barras ON produtos (codigo_barras);
CREATE INDEX IF NOT EXISTS idx_produto_nome_lower ON produtos (lower(nome));