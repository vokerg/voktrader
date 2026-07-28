CREATE TABLE fee_metadata (
    id BIGSERIAL PRIMARY KEY,
    market_id VARCHAR(128) NOT NULL,
    rate NUMERIC(20, 12) NOT NULL,
    exponent INTEGER NOT NULL,
    taker_only BOOLEAN NOT NULL,
    source VARCHAR(64) NOT NULL,
    effective_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_fee_metadata_rate_nonnegative CHECK (rate >= 0),
    CONSTRAINT chk_fee_metadata_exponent_nonnegative CHECK (exponent >= 0)
);

CREATE INDEX idx_fee_metadata_market_effective
    ON fee_metadata (market_id, effective_at DESC);
