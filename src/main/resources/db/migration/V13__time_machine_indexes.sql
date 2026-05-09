CREATE INDEX IF NOT EXISTS idx_price_snapshots_market_captured
    ON price_snapshots (market_id, captured_at);

CREATE INDEX IF NOT EXISTS idx_price_snapshots_market_bot_captured
    ON price_snapshots (market_id, bot_id, captured_at);

CREATE INDEX IF NOT EXISTS idx_trades_strategy_market_status_updated
    ON trades (strategy_id, market_id, status, updated_at);

CREATE INDEX IF NOT EXISTS idx_trades_strategy_market_token_status_updated
    ON trades (strategy_id, market_id, token_id, status, updated_at);

CREATE INDEX IF NOT EXISTS idx_trades_bot_strategy_market_status_updated
    ON trades (bot_id, strategy_id, market_id, status, updated_at);

CREATE INDEX IF NOT EXISTS idx_trades_bot_strategy_market_token_status_updated
    ON trades (bot_id, strategy_id, market_id, token_id, status, updated_at);

CREATE INDEX IF NOT EXISTS idx_trades_backtest_strategy_market_status_updated
    ON trades (backtest_run_id, strategy_id, market_id, status, updated_at);

CREATE INDEX IF NOT EXISTS idx_trades_backtest_strategy_market_token_status_updated
    ON trades (backtest_run_id, strategy_id, market_id, token_id, status, updated_at);

CREATE INDEX IF NOT EXISTS idx_trades_backtest_market_status
    ON trades (backtest_run_id, market_id, status);

CREATE INDEX IF NOT EXISTS idx_trade_orders_local_order
    ON trade_orders (local_order_id);

CREATE INDEX IF NOT EXISTS idx_trade_orders_client_order
    ON trade_orders (client_order_id);

CREATE INDEX IF NOT EXISTS idx_trade_orders_remote_order
    ON trade_orders (remote_order_id);

CREATE INDEX IF NOT EXISTS idx_trade_orders_status_updated
    ON trade_orders (status, updated_at);

CREATE INDEX IF NOT EXISTS idx_trade_fills_order_id
    ON trade_fills (order_id);

CREATE INDEX IF NOT EXISTS idx_trade_fills_remote_fill
    ON trade_fills (remote_fill_id);
