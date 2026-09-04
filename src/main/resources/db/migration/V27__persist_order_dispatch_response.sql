ALTER TABLE order_dispatch_outbox
    ADD COLUMN IF NOT EXISTS executor_status VARCHAR(128),
    ADD COLUMN IF NOT EXISTS executor_response TEXT,
    ADD COLUMN IF NOT EXISTS queue_latency_ms BIGINT,
    ADD COLUMN IF NOT EXISTS submit_rtt_ms BIGINT;

CREATE INDEX IF NOT EXISTS idx_order_dispatch_outbox_claim
    ON order_dispatch_outbox (state, next_attempt_at, created_at, id);
