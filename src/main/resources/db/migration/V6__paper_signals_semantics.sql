ALTER TABLE IF EXISTS fake_signals RENAME TO signals;

ALTER TABLE IF EXISTS signals RENAME COLUMN fake_size_usd TO paper_size_usd;
ALTER TABLE IF EXISTS signals RENAME COLUMN fake_shares TO paper_shares;
ALTER TABLE IF EXISTS signals RENAME COLUMN fake_pnl TO paper_pnl;
ALTER TABLE IF EXISTS signals RENAME COLUMN gross_fake_shares TO gross_paper_shares;
ALTER TABLE IF EXISTS signals RENAME COLUMN net_fake_shares TO net_paper_shares;

ALTER TABLE IF EXISTS signals ADD COLUMN IF NOT EXISTS signal_type VARCHAR(20);
UPDATE signals SET signal_type = 'PAPER' WHERE signal_type IS NULL;
ALTER TABLE IF EXISTS signals ALTER COLUMN signal_type SET NOT NULL;

ALTER TABLE IF EXISTS signals DROP CONSTRAINT IF EXISTS uk_fake_signal_market_token_rule;
ALTER TABLE IF EXISTS signals ADD CONSTRAINT uk_signal_market_token_rule_type
    UNIQUE (market_id, token_id, rule_name, signal_type);
