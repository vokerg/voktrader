# Implementation Report - T060

## Summary

T060 is complete. Backtests now reconstruct each replay order book from the exact persisted `market_depth_snapshot_levels`, validate the recorded book and captured depth feature vector before strategy execution, enforce T053 historical tick coverage, and fail closed on missing or corrupt replay ticks.

## Task

- Task ID: T060
- Task section: `transformation/tasks/PHASE-5-simulation-honesty.md#t060`
- Branch: `task/T060-replay-exact-depth-levels`
- PR: #18
- Status at completion: DONE

## Files changed

- `src/main/java/com/vokerg/voktrader/marketdata/MarketDepthReplayService.java`
  - Loads summary and ordered level rows for one market/capture timestamp.
  - Requires identical summary/level token sets.
  - Requires one summary per token, both sides, contiguous `levelIndex`, usable prices/sizes, and strict bid/ask ordering.
  - Reconstructs exact persisted `OutcomeOrderBook` values.
  - Recomputes best prices, spread, full and near-top depth, depth imbalances, buy-fill estimates, and book age against the stored live summary.
  - Enforces T053 dataset-aware tick coverage before returning a replay book.
  - Reports per-token bid/ask level counts and throws market/timestamp-specific corruption exceptions for blocked ticks.
- `src/main/java/com/vokerg/voktrader/backtest/BacktestReplayService.java`
  - Inventories depth tick coverage once before replay and logs blocker/resolved coverage counts.
  - Replaces summary-derived single bid/ask levels with exact replay books.
  - Injects every persisted bid and ask into `OrderBookState`.
  - Stops skipping ticks with missing token/depth data; incomplete or corrupt ticks now fail closed.
- `src/test/java/com/vokerg/voktrader/marketdata/MarketDepthReplayServiceTest.java`
  - Proves a golden replay tick equals the persisted levels exactly.
  - Proves captured depth-aware features equal values derived from the reconstructed book.
  - Covers missing-side, discontinuous-index, and summary-depth corruption blockers.
- `src/test/java/com/vokerg/voktrader/architecture/ExactDepthReplayArchitectureTest.java`
  - Prevents regression to summary-derived one-level backtest books.
- Transformation task ledger, index, and this report.

## Design decisions

- Persisted level rows are authoritative for replay depth.
- Replay preserves `levelIndex` and verifies that index order agrees with strict exchange-side price order.
- Summary rows are independent integrity witnesses, not replay depth inputs. Every captured depth-aware feature is recomputed from the exact levels and compared.
- Missing, duplicate, discontinuous, invalid, truncated, or summary-divergent rows are replay blockers rather than recoverable defaults.
- T053 historical tick coverage is inventoried before replay and checked for every token/tick.
- Existing price snapshots remain the replay clock and top-of-book price-state source; exact depth levels replace only the synthetic order-book construction.
- Pre-T060 backtest PnL is non-comparable because those runs used synthetic one-level books.

## Tests run

Final GitHub Actions run `30582543851` on head `24463395da7e442a230e4f8b204242b9b83a07e2`:

- `MarketDepthReplayServiceTest`: 4 passed.
- `ExactDepthReplayArchitectureTest`: 1 passed.
- Clean PostgreSQL migration: passed.
- Python tests: passed.
- Angular tests and production build: passed.
- Static repository/dependency checks: passed.
- Secret scan: passed.
- Java-plus-executor compose smoke: passed.
- Full Java suite: 301 tests, 7 failures, 18 errors, 2 skipped.
  - All five T060-specific tests pass.
  - Remaining failures/errors are the established strategy-boundary, runtime-state, and order-lifecycle baseline.
  - No CI gate was suppressed or weakened.

## Acceptance evidence

- Golden-tick replay equality is asserted over the complete ordered bid and ask lists.
- Depth-feature parity is asserted for best bid/ask, spread, total depth, near-top depth, full and near imbalance, buy-fill shares/average/worst price/completion/levels consumed, and book age.
- The production backtest path is guarded against reintroducing one-level summary reconstruction.

## Safety impact

This task changes historical replay construction and validation only. It does not enable live capital or change live strategy thresholds, risk, routing, cancellation, reconciliation, settlement, tick, or fee semantics. Invalid datasets now stop replay rather than producing misleading simulated decisions.

## Backward compatibility

- Snapshot schema is unchanged.
- Live capture and live order-book behavior are unchanged.
- Backtests over complete, internally consistent depth rows now receive the exact recorded books.
- Backtests that relied on missing, truncated, or inconsistent depth data intentionally fail closed.
- Historical PnL generated before T060 should be archived or labelled non-comparable.

## Remaining risks

- Retained datasets whose configured level cap omitted depth represented by the summary row will be rejected as truncated. This is intentional simulation-honesty behavior and requires a better dataset rather than reconstruction heuristics.
- Existing Java lifecycle baseline failures remain outside T060 and are not suppressed.

## Follow-up tasks

None. T062 can build broader live/replay parity once its remaining dependencies are complete, and T063 can consume the exact books once settlement prerequisites are complete.

## Completion checklist

- [x] Acceptance criteria met
- [x] Tests added/updated
- [x] Tests run and results recorded
- [x] Task section status updated
- [x] Task index updated
- [x] No unrelated strategy tuning included
