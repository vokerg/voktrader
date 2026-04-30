# voktrader Python executor sidecar

This sidecar is intentionally separated from the JVM strategy/risk engine. The Java app keeps deciding and journaling trades; this Python process is the narrow adapter that can place Polymarket CLOB V2 orders.

## Safety defaults

- Java live profile has `voktrader.executor.enabled=false`.
- Sidecar has `EXECUTOR_DRY_RUN=true`.
- Sidecar locally caps `MAX_ORDER_AMOUNT_USD=5`.
- Sidecar defaults to FOK-only orders through `REQUIRE_FOK=true`.
- The JVM live lifecycle defaults to `voktrader.executor.require-immediate-fill=true` because no async exchange-reconciliation loop is included here.

## Local dry-run smoke test

```bash
cd executor-python
python -m venv .venv
. .venv/bin/activate
pip install -e .
cp .env.example .env
uvicorn voktrader_executor.main:app --host 127.0.0.1 --port 8099
```

In another shell:

```bash
curl -sS http://127.0.0.1:8099/health
curl -sS \
  -H 'Authorization: Bearer change-me' \
  -H 'Content-Type: application/json' \
  -d '{
    "idempotencyKey":"smoke:1",
    "strategyId":"smoke",
    "marketId":"market",
    "tokenId":"123",
    "side":"BUY",
    "amountUsd":"1.00",
    "limitPrice":"0.50",
    "timeInForce":"FOK",
    "dryRun":true
  }' \
  http://127.0.0.1:8099/v1/orders
```

## Enabling real exchange calls

Only after dry-run testing:

1. Fund and configure the wallet used for Polymarket CLOB V2.
2. Set `EXECUTOR_DRY_RUN=false` in `executor-python/.env`.
3. Set `POLYMARKET_PRIVATE_KEY`, `POLYMARKET_FUNDER` where applicable, and optional API creds.
4. Keep `MAX_ORDER_AMOUNT_USD` tiny until reconciliation and operations are proven.
5. Start Java with `spring.profiles.active=live`, then set `voktrader.executor.enabled=true` and `voktrader.executor.dry-run=false` deliberately.

This package does not add a background order reconciliation worker. With `require-immediate-fill=true`, accepted-but-unfilled live orders are treated as failed in the JVM ledger to avoid pretending there is an open filled position.
