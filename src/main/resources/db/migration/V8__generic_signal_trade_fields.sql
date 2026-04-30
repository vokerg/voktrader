ALTER TABLE IF EXISTS signals ADD COLUMN IF NOT EXISTS size_usd NUMERIC(19, 8);
ALTER TABLE IF EXISTS signals ADD COLUMN IF NOT EXISTS shares NUMERIC(19, 8);
ALTER TABLE IF EXISTS signals ADD COLUMN IF NOT EXISTS gross_shares NUMERIC(19, 8);
ALTER TABLE IF EXISTS signals ADD COLUMN IF NOT EXISTS net_shares NUMERIC(19, 8);
ALTER TABLE IF EXISTS signals ADD COLUMN IF NOT EXISTS pnl_usd NUMERIC(19, 8);

UPDATE signals SET size_usd = paper_size_usd WHERE size_usd IS NULL AND paper_size_usd IS NOT NULL;
UPDATE signals SET shares = paper_shares WHERE shares IS NULL AND paper_shares IS NOT NULL;
UPDATE signals SET gross_shares = gross_paper_shares WHERE gross_shares IS NULL AND gross_paper_shares IS NOT NULL;
UPDATE signals SET net_shares = net_paper_shares WHERE net_shares IS NULL AND net_paper_shares IS NOT NULL;
UPDATE signals SET pnl_usd = paper_pnl WHERE pnl_usd IS NULL AND paper_pnl IS NOT NULL;

ALTER TABLE IF EXISTS signals DROP COLUMN IF EXISTS paper_size_usd;
ALTER TABLE IF EXISTS signals DROP COLUMN IF EXISTS paper_shares;
ALTER TABLE IF EXISTS signals DROP COLUMN IF EXISTS gross_paper_shares;
ALTER TABLE IF EXISTS signals DROP COLUMN IF EXISTS net_paper_shares;
ALTER TABLE IF EXISTS signals DROP COLUMN IF EXISTS paper_pnl;

ALTER TABLE IF EXISTS signals ADD COLUMN IF NOT EXISTS decision_bid NUMERIC(19, 8);
ALTER TABLE IF EXISTS signals ADD COLUMN IF NOT EXISTS decision_ask NUMERIC(19, 8);
ALTER TABLE IF EXISTS signals ADD COLUMN IF NOT EXISTS decision_spread NUMERIC(19, 8);
ALTER TABLE IF EXISTS signals ADD COLUMN IF NOT EXISTS decision_price_updated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE IF EXISTS signals ADD COLUMN IF NOT EXISTS snapshot_age_ms BIGINT;
