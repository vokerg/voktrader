ALTER TABLE IF EXISTS trade_risk_checks
    ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_trade_risk_checks_correlation
    ON trade_risk_checks(correlation_id);
