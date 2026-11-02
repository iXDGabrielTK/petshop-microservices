-- V7__pagamento_contabilizado.sql
-- Saneamento idempotente do pipeline de pagamento (DEBT-ARCH-003 / E1 - F4, Q8)
-- Adiciona coluna 'contabilizado' com default false e backfill seguro para pagamentos confirmados

ALTER TABLE pagamentos ADD COLUMN IF NOT EXISTS contabilizado BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE pagamentos p
   SET contabilizado = TRUE
  FROM vendas v
 WHERE p.venda_id = v.id
   AND p.status = 'CONFIRMADO'
   AND p.excedente = FALSE
   AND v.valor_pago > 0;
