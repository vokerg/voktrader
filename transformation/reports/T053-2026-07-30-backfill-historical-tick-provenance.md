# Implementation Report - T053

## Summary

Implemented explicit, auditable tick provenance for replay datasets captured before dynamic tick persistence. Retained intervals are inventoried into sourced `RESOLVED` coverage or explicit `BLOCKED` coverage. Documented REST-book and WebSocket evidence can be imported through a controlled JSON Lines runner, and historical tick resolution now fails closed on active blockers, missing dataset coverage, or missing timeline metadata.

No tick is inferred from price precision or level spacing, and no implicit `0.01` fallback is introduced.

## Task

- Task ID: T053
- Task section: `transformation/tasks/PHASE-4-protocol-and-control-plane.md#t053`
- Branch: `task/T053-historical-tick-provenance`
- PR: #17
- Status at completion: DONE
- Completed: 2026-07-30T20:52:07Z

## Inventory findings

- `price_snapshots` retains top-of-book prices and capture timestamps but no token ID or protocol `tick_size` evidence.
- `market_depth_snapshot_levels` retains token IDs, price levels, and capture timestamps but not the REST order-book `tick_size` field.
- Decimal precision and observed price spacing are not authoritative exchange-tick evidence.
- `tick_size_metadata` is the existing authoritative timeline sourced from REST books and `tick_size_change` events.
- Legacy price-snapshot intervals require timestamped market-to-token evidence for every replay token; until then they remain explicit market-level blockers.

## Files changed

- `src/main/resources/db/migration/V24__historical_tick_coverage.sql`
  - Adds constrained append-only resolved/blocker coverage storage and replay lookup indexes.
- `src/main/java/com/vokerg/voktrader/marketdata/HistoricalTickDatasetType.java`
- `src/main/java/com/vokerg/voktrader/marketdata/HistoricalTickCoverageStatus.java`
- `src/main/java/com/vokerg/voktrader/marketdata/HistoricalTickEvidenceSource.java`
  - Define dataset, coverage, and evidence vocabulary without a default-tick state.
- `src/main/java/com/vokerg/voktrader/marketdata/model/HistoricalTickCoverageEntity.java`
  - Enforces token, positive tick, evidence source/reference, and blocker invariants.
- `src/main/java/com/vokerg/voktrader/marketdata/persistence/HistoricalTickCoverageRepository.java`
  - Supports idempotent inventory, append-only supersession, and replay-time interval queries.
- `src/main/java/com/vokerg/voktrader/marketdata/persistence/MarketDepthSnapshotLevelRepository.java`
  - Inventories retained market/token depth intervals.
- `src/main/java/com/vokerg/voktrader/marketdata/persistence/PriceSnapshotRepository.java`
  - Inventories retained legacy price-snapshot market intervals, including legacy/entity market identity.
- `src/main/java/com/vokerg/voktrader/marketdata/persistence/TickSizeMetadataRepository.java`
  - Adds earliest-authoritative-observation lookup.
- `src/main/java/com/vokerg/voktrader/marketdata/HistoricalTickCoverageService.java`
  - Persists blocker prefixes and sourced resolved suffixes for depth data.
  - Persists market-level blockers for price data lacking token identity.
  - Imports verified REST/WebSocket evidence into the authoritative tick timeline and coverage ledger.
  - Rejects active blockers, missing dataset coverage, and missing historical metadata.
- `src/main/java/com/vokerg/voktrader/marketdata/HistoricalTickEvidenceImportRunner.java`
  - Property-gated JSON Lines maintenance runner that inventories before importing evidence.
- `src/main/java/com/vokerg/voktrader/marketdata/TickSizeService.java`
  - Enforces token coverage during TimeMachine lookup and adds dataset-aware historical execution.
- `docs/historical-tick-backfill.md`
  - Documents accepted evidence, JSON Lines format, controlled execution, and replay enforcement.
- `src/test/java/com/vokerg/voktrader/marketdata/HistoricalTickCoverageServiceTest.java`
- `src/test/java/com/vokerg/voktrader/marketdata/TickSizeReplayCoverageGuardTest.java`
- `src/test/java/com/vokerg/voktrader/architecture/HistoricalTickBackfillArchitectureTest.java`
  - Cover inventory, import, supersession, fail-closed replay, and absence of an implicit cent tick.
- Transformation task ledger, index, and this report.

## Design decisions

1. **Never infer protocol metadata from prices.** Recorded decimals and level spacing can be compatible with multiple exchange tick grids.
2. **Represent the complete interval.** Depth intervals receive an unresolved prefix before the first authoritative observation and a sourced resolved suffix from that observation onward.
3. **Block legacy price data at market scope.** Price snapshots do not contain token identity, so inventory records a market-level blocker rather than inventing a mapping.
4. **Append, do not rewrite.** Later verified token resolutions supersede earlier blockers by recorded order while retaining the audit trail.
5. **Accept only documented protocol evidence.** Imports allow retained REST `tick_size` values and retained WebSocket tick-change events with a required source reference.
6. **Fail closed at replay resolution.** Dataset-aware replay requires explicit coverage; TimeMachine token lookup rejects active token blockers before reading metadata.
7. **Keep import opt-in.** The maintenance runner is disabled unless `voktrader.historical-tick.evidence-file` is set.

## Tests run

GitHub Actions run `30580949440` on implementation head `601f5eafa954f449e864d291ef43ed6c951e0e02`.

Focused Java evidence:

- `HistoricalTickCoverageServiceTest`: 8 passed.
- `TickSizeReplayCoverageGuardTest`: 3 passed.
- `HistoricalTickBackfillArchitectureTest`: 1 passed.
- Existing `TickSizeServiceTest`: 2 passed.

Other passing jobs:

- Clean PostgreSQL migration including V24.
- Python tests.
- Angular tests and production build.
- Static repository/dependency checks.
- Secret scan.
- Java-plus-executor compose smoke.

Full Java suite result:

- 296 tests, 7 failures, 18 errors, 2 skipped.
- All T053-specific tests pass.
- Remaining failures/errors are the established architecture, runtime-state, and order-lifecycle baseline; no CI gate was suppressed or weakened.

## Safety impact

This task changes historical replay-data provenance and validation only. It does not enable live capital or change live strategy thresholds, risk, routing, cancellation, reconciliation, settlement, or fee semantics. Unresolved historical data now blocks replay rather than silently using an assumed tick.

## Backward compatibility

- V24 is additive.
- Existing snapshots are not modified.
- Existing authoritative tick observations remain unchanged.
- The existing token-only historical execution overload remains available and now rejects active token blockers when coverage enforcement is wired.
- Dataset-aware replay is additive and provides stronger fail-closed semantics.
- The evidence runner is disabled by default.

## Remaining risks

- The maintenance runner must be executed against each retained operational dataset to materialize its actual coverage rows.
- Legacy price snapshots remain blocked unless operators possess retained timestamped evidence for every replay token.
- Source artifacts remain external references; operators must preserve them under their evidence-retention process.
- Existing Java lifecycle failures remain outside T053 and are not suppressed.

## Follow-up tasks

No new task is required. T060 can consume dataset-aware historical tick coverage while implementing exact recorded-depth replay.

## Completion checklist

- [x] Acceptance criteria met
- [x] Tests added/updated
- [x] Tests run and exact results recorded
- [x] Task section status updated
- [x] Task index updated
- [x] No unrelated strategy tuning included
