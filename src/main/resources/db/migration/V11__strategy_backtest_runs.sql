CREATE TABLE IF NOT EXISTS strategy_backtest_runs (
    id VARCHAR(64) PRIMARY KEY,
    strategy_id VARCHAR(255) NOT NULL,
    market_ids VARCHAR(4000),
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    snapshot_count BIGINT,
    trade_count BIGINT,
    closed_trade_count BIGINT,
    open_trade_count BIGINT,
    total_fee_usd NUMERIC(19, 8),
    final_pnl_usd NUMERIC(19, 8)
);

ALTER TABLE trades ADD COLUMN IF NOT EXISTS backtest_run_id VARCHAR(64);

ALTER TABLE trades ALTER COLUMN mode SET DATA TYPE VARCHAR(32);
ALTER TABLE trade_orders ALTER COLUMN mode SET DATA TYPE VARCHAR(32);
ALTER TABLE trade_orders ALTER COLUMN venue SET DATA TYPE VARCHAR(32);
ALTER TABLE trade_risk_checks ALTER COLUMN mode SET DATA TYPE VARCHAR(32);
ALTER TABLE trade_fills ALTER COLUMN venue SET DATA TYPE VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_trades_backtest_run
    ON trades (backtest_run_id);
