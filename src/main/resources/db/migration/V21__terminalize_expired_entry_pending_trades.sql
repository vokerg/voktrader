UPDATE trades t
SET status = 'CANCELLED',
    updated_at = CURRENT_TIMESTAMP
WHERE t.status IN ('CREATED', 'ENTRY_PENDING')
  AND EXISTS (
      SELECT 1
      FROM trade_orders o
      WHERE o.trade_id = t.id
        AND o.phase = 'ENTRY'
        AND o.status = 'EXPIRED'
        AND COALESCE(o.filled_shares, 0) = 0
  )
  AND NOT EXISTS (
      SELECT 1
      FROM trade_fills f
      WHERE f.trade_id = t.id
        AND COALESCE(f.shares, 0) > 0
  );
