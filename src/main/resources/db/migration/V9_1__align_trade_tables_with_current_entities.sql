-- The trade model moved from the first execution ledger shape to the current
-- client_order_id/order_id based entities. Keep old columns readable, but stop
-- requiring writes to columns that the current entities no longer own.

ALTER TABLE IF EXISTS trade_orders ADD COLUMN IF NOT EXISTS client_order_id VARCHAR(255);
ALTER TABLE IF EXISTS trade_orders ADD COLUMN IF NOT EXISTS strategy_id VARCHAR(255);
ALTER TABLE IF EXISTS trade_orders ADD COLUMN IF NOT EXISTS rule_id VARCHAR(255);
ALTER TABLE IF EXISTS trade_orders ADD COLUMN IF NOT EXISTS market_id VARCHAR(255);
ALTER TABLE IF EXISTS trade_orders ADD COLUMN IF NOT EXISTS requested_price NUMERIC(19, 8);
ALTER TABLE IF EXISTS trade_orders ADD COLUMN IF NOT EXISTS filled_price NUMERIC(19, 8);

UPDATE trade_orders
SET client_order_id = idempotency_key
WHERE client_order_id IS NULL
  AND idempotency_key IS NOT NULL;

ALTER TABLE IF EXISTS trade_orders DROP CONSTRAINT IF EXISTS uk_trade_orders_idempotency_key;
ALTER TABLE IF EXISTS trade_orders ALTER COLUMN idempotency_key DROP NOT NULL;
ALTER TABLE IF EXISTS trade_orders ALTER COLUMN phase DROP NOT NULL;
ALTER TABLE IF EXISTS trade_orders ALTER COLUMN mode DROP NOT NULL;
ALTER TABLE IF EXISTS trade_orders ALTER COLUMN venue DROP NOT NULL;

ALTER TABLE IF EXISTS trade_fills ADD COLUMN IF NOT EXISTS order_id BIGINT;
ALTER TABLE IF EXISTS trade_fills ADD COLUMN IF NOT EXISTS filled_at TIMESTAMP;

UPDATE trade_fills
SET order_id = trade_order_id
WHERE order_id IS NULL
  AND trade_order_id IS NOT NULL;

UPDATE trade_fills
SET filled_at = occurred_at
WHERE filled_at IS NULL
  AND occurred_at IS NOT NULL;

ALTER TABLE IF EXISTS trade_fills ALTER COLUMN trade_order_id DROP NOT NULL;
ALTER TABLE IF EXISTS trade_fills ALTER COLUMN occurred_at DROP NOT NULL;
ALTER TABLE IF EXISTS trade_fills ALTER COLUMN received_at DROP NOT NULL;
