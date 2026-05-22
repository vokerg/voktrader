ALTER TABLE IF EXISTS trade_orders
    ADD COLUMN IF NOT EXISTS last_reconcile_attempt_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS consecutive_reconcile_failures INTEGER,
    ADD COLUMN IF NOT EXISTS next_reconcile_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS reconciliation_paused_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS reconciliation_pause_reason TEXT;

CREATE INDEX IF NOT EXISTS idx_trade_orders_reconcile_due
    ON trade_orders (reconciliation_paused_at, next_reconcile_at, updated_at);
