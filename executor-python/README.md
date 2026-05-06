# voktrader Python executor sidecar

This sidecar is intentionally separated from the JVM strategy/risk engine. The Java app keeps deciding and journaling trades; this Python process is the narrow adapter that can place Polymarket CLOB V2 orders.

## Safety defaults

- Java live profile uses `LIVE_TINY`, keeps `max-order-usd=1.00`, and allows one open live trade.
- Java live profile has `voktrader.executor.enabled=true` and `voktrader.executor.dry-run=false`.
- Sidecar has `EXECUTOR_DRY_RUN=false`.
- Sidecar locally caps `MAX_ORDER_AMOUNT_USD=5`.
- Sidecar lets the JVM strategy choose order type through `REQUIRE_FOK=false`.
- The JVM live lifecycle lets resting maker orders enter `ENTRY_PENDING` through `voktrader.executor.require-immediate-fill=false`.

With the auth values blank, the sidecar is live-ready but cannot place an order. A trade attempt should fail with `POLYMARKET_PRIVATE_KEY is required when EXECUTOR_DRY_RUN=false`.

## Local executor startup

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
    "postOnly":false,
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
5. Start Java with `spring.profiles.active=live`.

For taker-style FOK/FAK orders the sidecar uses the SDK market-order path. For resting maker-style GTC/GTD orders it uses the SDK limit-order path and sends `postOnly=true` when requested by the JVM.

This package does not add a background order reconciliation worker. With `REQUIRE_FOK=false` and `voktrader.executor.require-immediate-fill=false`, strategies can submit resting maker orders; accepted-but-unfilled orders are stored as `ENTRY_PENDING` until reconciliation is added. Use FOK/FAK strategies when you need immediate filled ledger entries.
