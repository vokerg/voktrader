# Implementation Report - T060

## Summary

Implementation is in progress. The first integrated slice replaces synthetic one-level backtest books with exact persisted depth levels, validates snapshot integrity against the captured summary feature vector, enforces T053 tick coverage, and rejects incomplete or corrupt ticks.

## Task

- Task ID: T060
- Task section: `transformation/tasks/PHASE-5-simulation-honesty.md#t060`
- Branch: `task/T060-replay-exact-depth-levels`
- PR: #18
- Status at completion: IN_PROGRESS

## Files changed

- `src/main/java/com/vokerg/voktrader/marketdata/MarketDepthReplayService.java`
  - Loads summary and ordered level rows for one market/capture timestamp.
  - Requires identical summary/level token sets.
  - Requires both sides, contiguous `levelIndex`, usable prices/sizes, and strict bid/ask ordering.
  - Reconstructs exact `OutcomeOrderBook` values.
  - Recomputes best prices, spread, full/near depth, imbalance, buy estimate, and age against the stored live summary.
  - Enforces T053 dataset-aware tick coverage before returning a replay book.
- `src/main/java/com/vokerg/voktrader/backtest/BacktestReplayService.java`
  - Inventories depth tick coverage once before replay.
  - Replaces summary-derived single bid/ask levels with exact replay books.
  - Stops skipping ticks with missing token/depth data; incomplete or corrupt ticks now fail closed.
- `src/test/java/com/vokerg/voktrader/marketdata/MarketDepthReplayServiceTest.java`
  - Golden exact-book and depth-feature parity coverage.
  - Missing-side, discontinuous-index, and summary-depth corruption coverage.
- `src/test/java/com/vokerg/voktrader/architecture/ExactDepthReplayArchitectureTest.java`
  - Prevents regression to summary-derived one-level backtest books.
- Transformation task ledger, index, and this report.

## Design decisions

- Persisted level rows are authoritative for replay depth.
- Replay preserves `levelIndex` and verifies that index order agrees with strict exchange-side price order.
- Summary rows are independent integrity witnesses, not replay inputs. Every depth-aware captured feature is recomputed from levels and compared.
- Missing, duplicate, discontinuous, invalid, truncated, or summary-divergent rows are replay blockers rather than recoverable defaults.
- T053 historical tick coverage is inventoried before replay and checked for each token/tick.
- Existing price snapshots remain the replay clock and top-of-book price-state source; exact depth levels replace only the synthetic order-book construction.

## Tests run

Initial loader run `30582023780` compiled successfully. Three loader tests passed; one assertion expected a later injected index while the production validator correctly reported the first gap. The assertion was corrected on commit `70258252992e5dec0c92ed4ca68299a351afafd8`.

Integrated CI run `30582461337` is running on head `aa8c3a241089dda0ca23784ae3f4eeefaead0ce2`.

## Safety impact

This task changes historical replay construction and validation only. It does not enable live capital or change live strategy thresholds, risk, routing, cancellation, reconciliation, settlement, tick, or fee semantics. Invalid datasets now stop replay rather than producing misleading simulated decisions.

## Backward compatibility

- Snapshot schema is unchanged.
- Live capture and live order-book behavior are unchanged.
- Backtests over complete, internally consistent depth rows now receive the exact recorded books.
- Backtests that relied on missing, truncated, or inconsistent depth data intentionally fail closed.

## Remaining risks

- Integrated Java/compose validation is still running.
- Coverage reporting is currently present in loader results and preload logs; final response/API exposure may require refinement after validation.
- Historical datasets persisted with fewer levels than represented by summary total depth will be rejected as truncated, as required for simulation honesty.
- Existing Java lifecycle baseline failures remain outside T060 and are not suppressed.

## Follow-up tasks

None identified yet.

## Completion checklist

- [ ] Acceptance criteria met
- [x] Tests added/updated
- [ ] Tests run and results recorded
- [x] Task section status updated
- [x] Task index updated
- [x] No unrelated strategy tuning included
