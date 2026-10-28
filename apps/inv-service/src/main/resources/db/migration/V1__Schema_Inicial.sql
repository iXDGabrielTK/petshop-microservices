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

-- 6. Tabela LANCAMENTOS-FINANCEIROS
CREATE TABLE lancamentos_financeiros (
                                         id BIGSERIAL PRIMARY KEY,
                                         venda_id BIGINT NOT NULL,
                                         referencia UUID NOT NULL,
                                         tipo VARCHAR(20) NOT NULL,
                                         valor NUMERIC(10, 2) NOT NULL,
                                         data_hora TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                         CONSTRAINT fk_lancamento_venda FOREIGN KEY (venda_id) REFERENCES vendas(id),
                                         CONSTRAINT uk_lancamento_referencia UNIQUE (referencia)
);

-- 7. Tabela FINANCIAL_PROJECTION_VENDA
CREATE TABLE financial_projection_venda (
                                            venda_id BIGINT PRIMARY KEY,
                                            saldo NUMERIC(10,2) NOT NULL DEFAULT 0,
                                            total_creditos NUMERIC(10,2) NOT NULL DEFAULT 0,
                                            total_estornos NUMERIC(10,2) NOT NULL DEFAULT 0,
                                            ultima_atualizacao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 8. Tabela FINANCIAL_PROJECTION_CHECKPOINT
CREATE TABLE financial_projection_checkpoint (
                                                 lancamento_id BIGINT PRIMARY KEY,
                                                 processado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                 status VARCHAR(20) NOT NULL DEFAULT 'OK'
);

-- 9. Tabela FINANCIAL_RECONCILIATION_CHECKPOINT
CREATE TABLE financial_reconciliation_checkpoint (
                                                     id SERIAL PRIMARY KEY,
                                                     ultimo_lancamento_id BIGINT NOT NULL,
                                                     verificado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                     CONSTRAINT uk_recon_watermark UNIQUE (ultimo_lancamento_id)
);

-- 10. Tabela PROJECTION_RETRY_QUEUE
CREATE TABLE projection_retry_queue (
                                        id BIGSERIAL PRIMARY KEY,
                                        lancamento_id BIGINT NOT NULL,
                                        payload JSONB NOT NULL,
                                        erro TEXT,
                                        tentativas INT DEFAULT 0,
                                        status VARCHAR(20) NOT NULL DEFAULT 'PENDENTE',
                                        proxima_execucao TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                        CONSTRAINT uk_retry_lancamento UNIQUE (lancamento_id)
);

-- 11. Tabela FECHAMENTO_CAIXA_DIARIO
CREATE TABLE fechamento_caixa_diario (
                                         data_referencia DATE PRIMARY KEY,
                                         saldo_inicial NUMERIC(10,2) NOT NULL,
                                         total_creditos NUMERIC(10,2) NOT NULL,
                                         total_debitos NUMERIC(10,2) NOT NULL,
                                         saldo_final NUMERIC(10,2) NOT NULL,
                                         ultimo_lancamento_id BIGINT NOT NULL,
                                         processado_em TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 12. Índices de Performance
CREATE INDEX IF NOT EXISTS idx_produto_codigo_barras ON produtos (codigo_barras);
CREATE INDEX IF NOT EXISTS idx_produto_nome_lower ON produtos (lower(nome));
CREATE INDEX idx_lancamento_venda_id ON lancamentos_financeiros(venda_id);