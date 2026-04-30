ALTER TABLE IF EXISTS fake_signals ADD COLUMN IF NOT EXISTS fee_rate NUMERIC(19, 8);
ALTER TABLE IF EXISTS fake_signals ADD COLUMN IF NOT EXISTS entry_fee_usd NUMERIC(19, 8);
ALTER TABLE IF EXISTS fake_signals ADD COLUMN IF NOT EXISTS gross_fake_shares NUMERIC(19, 8);
ALTER TABLE IF EXISTS fake_signals ADD COLUMN IF NOT EXISTS net_fake_shares NUMERIC(19, 8);

UPDATE fake_signals
SET fee_rate = 0,
    entry_fee_usd = 0,
    gross_fake_shares = fake_shares,
    net_fake_shares = fake_shares
WHERE gross_fake_shares IS NULL
  AND fake_shares IS NOT NULL;
