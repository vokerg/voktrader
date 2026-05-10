# Strategy V2 Manifest Pack

This directory is the LLM-readable contract for Strategy V2. Read `llm.index.yml` first, then follow its `load_order`.

Strategy V2 YAML has one root object, `strategy-v2`, containing engine settings, optional defaults, and `strategies[]`. A bot/backtest runs the Java strategy id `strategy-v2`; the internal config strategy is chosen by `strategy-v2.engine.active-strategy-ids`.

Fees, maker/taker behavior, resolution payout, and fill assumptions are first-class Strategy V2 building blocks. They are not hidden implementation details.

Smoke-test examples verify plumbing and may be economically silly. Real examples are trading hypotheses and must still be validated by backtests. FOK/taker strategies pay taker fees and cross spread, so they need larger short-horizon edge. Maker entries avoid taker entry fees in the order-layer backtest, but fill realism depends on the configured backtest fill model. Resolution exits are not normal YAML `SELL_NOW` exits; they happen when unresolved open trades are resolved after replay.

Current Strategy V2 paper runs use DB runtime state and the legacy backtest execution override unless `voktrader.strategy-v2.execution.use-order-layer=true`. The order-layer backtest supports maker order lifecycle and maker zero-fee fills; the legacy override fills Strategy V2 FOK/taker intents immediately through book-aware replay context.

