-- V2__ajusta_schema_produtos_vendas_pagamentos.sql

-- 1. Ajuste na tabela PRODUTOS
ALTER TABLE produtos ADD COLUMN IF NOT EXISTS estoque_disponivel NUMERIC(10, 3) NOT NULL DEFAULT 0;
ALTER TABLE produtos ADD COLUMN IF NOT EXISTS estoque_reservado NUMERIC(10, 3) NOT NULL DEFAULT 0;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='produtos' AND column_name='quantidade_estoque') THEN
        UPDATE produtos SET estoque_disponivel = quantidade_estoque WHERE estoque_disponivel = 0;
        ALTER TABLE produtos DROP COLUMN quantidade_estoque;
    END IF;
END $$;

ALTER TABLE produtos ALTER COLUMN version SET DEFAULT 0;
UPDATE produtos SET version = 0 WHERE version IS NULL;
ALTER TABLE produtos ALTER COLUMN version SET NOT NULL;

-- 2. Ajuste na tabela VENDAS
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='vendas' AND column_name='data_hora') THEN
        ALTER TABLE vendas RENAME COLUMN data_hora TO data_hora_abertura;
    END IF;
END $$;

ALTER TABLE vendas ADD COLUMN IF NOT EXISTS data_hora_abertura TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE vendas ADD COLUMN IF NOT EXISTS data_hora_conclusao TIMESTAMP;
ALTER TABLE vendas ADD COLUMN IF NOT EXISTS status VARCHAR(50) NOT NULL DEFAULT 'ABERTA';
ALTER TABLE vendas ADD COLUMN IF NOT EXISTS valor_pago NUMERIC(10, 2) NOT NULL DEFAULT 0;
ALTER TABLE vendas ADD COLUMN IF NOT EXISTS valor_troco NUMERIC(10, 2) NOT NULL DEFAULT 0;
ALTER TABLE vendas ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- 3. Criação da tabela PAGAMENTOS
CREATE TABLE IF NOT EXISTS pagamentos (
    id BIGSERIAL PRIMARY KEY,
    uuid_referencia UUID NOT NULL,
    venda_id BIGINT NOT NULL,
    metodo VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDENTE',
    valor_esperado NUMERIC(10, 2) NOT NULL,
    valor_recebido NUMERIC(10, 2) DEFAULT 0,
    excedente BOOLEAN NOT NULL DEFAULT FALSE,
    nsu_transacao VARCHAR(255),
    data_hora_pagamento TIMESTAMP,
    CONSTRAINT fk_pagamentos_venda FOREIGN KEY (venda_id) REFERENCES vendas(id),
    CONSTRAINT uk_pagamento_uuid UNIQUE (uuid_referencia)
);

CREATE INDEX IF NOT EXISTS idx_pagamento_venda_id ON pagamentos(venda_id);

-- 4. Ajuste na tabela FINANCIAL_RECONCILIATION_CHECKPOINT
ALTER TABLE financial_reconciliation_checkpoint ALTER COLUMN id TYPE BIGINT;
