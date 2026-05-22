ALTER TABLE IF EXISTS trade_orders
    ADD COLUMN IF NOT EXISTS consecutive_reconcile_no_progress INTEGER,
    ADD COLUMN IF NOT EXISTS last_reconcile_progress_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS last_reconcile_progress_summary TEXT;
