ALTER TABLE trade_fills ADD COLUMN IF NOT EXISTS remote_fill_key VARCHAR(1000);
ALTER TABLE trade_fills ALTER COLUMN remote_fill_key VARCHAR(1000);

CREATE UNIQUE INDEX IF NOT EXISTS idx_trade_fills_remote_key
    ON trade_fills(remote_fill_key);
