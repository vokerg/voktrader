UPDATE trades
SET mode = 'LIVE'
WHERE mode IN ('LIVE_TINY', 'LIVE_SHADOW');

UPDATE trade_orders
SET mode = 'LIVE'
WHERE mode IN ('LIVE_TINY', 'LIVE_SHADOW');

UPDATE trade_risk_checks
SET mode = 'LIVE'
WHERE mode IN ('LIVE_TINY', 'LIVE_SHADOW');

UPDATE trades
SET mode = 'BACKTEST'
WHERE mode = 'TESTING';

UPDATE trade_orders
SET mode = 'BACKTEST'
WHERE mode = 'TESTING';

UPDATE trade_risk_checks
SET mode = 'BACKTEST'
WHERE mode = 'TESTING';
