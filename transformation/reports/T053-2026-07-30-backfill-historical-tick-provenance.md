# Implementation Report - T053

## Summary

Implementation is in progress. The first slice inventories retained depth-snapshot token intervals against the earliest authoritative tick observation, persists explicit unresolved coverage, and refuses to infer an exchange tick from recorded price levels.

## Task

- Task ID: T053
- Task section: `transformation/tasks/PHASE-4-protocol-and-control-plane.md#t053`
- Branch: `task/T053-historical-tick-provenance`
- PR: #17
- Status at completion: IN_PROGRESS

## Inventory findings

- `price_snapshots` retains top-of-book prices and capture timestamps but no token ID or protocol `tick_size` evidence.
- `market_depth_snapshot_levels` retains token IDs, price levels, and capture timestamps but does not retain the REST order-book `tick_size` field.
- Price alignment or decimal precision is not authoritative evidence of the exchange tick; multiple tick grids can contain the same observed prices.
- `tick_size_metadata` is the only existing persisted authoritative timeline, sourced from REST books and `tick_size_change` events after T041.
- Therefore, pre-metadata depth intervals must initially be blocked unless a separate documented REST/event observation is imported later.

## Files changed

- `src/main/resources/db/migration/V24__historical_tick_coverage.sql`
  - Adds constrained historical coverage/provenance storage and interval/status indexes.
- `src/main/java/com/vokerg/voktrader/marketdata/HistoricalTickDatasetType.java`
- `src/main/java/com/vokerg/voktrader/marketdata/HistoricalTickCoverageStatus.java`
- `src/main/java/com/vokerg/voktrader/marketdata/HistoricalTickEvidenceSource.java`
  - Define dataset, coverage, and evidence vocabulary without a default-tick state.
- `src/main/java/com/vokerg/voktrader/marketdata/model/HistoricalTickCoverageEntity.java`
  - Append-oriented resolved/blocker factories enforce evidence invariants in application code.
- `src/main/java/com/vokerg/voktrader/marketdata/persistence/HistoricalTickCoverageRepository.java`
  - Supports idempotent exact-interval inventory writes.
- `src/main/java/com/vokerg/voktrader/marketdata/persistence/MarketDepthSnapshotLevelRepository.java`
  - Adds an aggregate projection for retained market/token capture intervals.
- `src/main/java/com/vokerg/voktrader/marketdata/persistence/TickSizeMetadataRepository.java`
  - Adds earliest-authoritative-observation lookup.
- `src/main/java/com/vokerg/voktrader/marketdata/HistoricalTickCoverageService.java`
  - Records the uncovered prefix before the first authoritative observation as an explicit blocker; intervals covered from their start by the tick timeline require no backfill.
- `src/test/java/com/vokerg/voktrader/marketdata/HistoricalTickCoverageServiceTest.java`
  - Covers absent evidence, late first evidence, and evidence available at interval start.
- Transformation claim ledger, index, and this report.

## Design decisions

- Never infer historical tick metadata from current metadata, recorded price precision, or level spacing.
- Treat coverage intervals as start-inclusive/end-exclusive when the first authoritative observation cuts off an unresolved prefix.
- Store resolved and blocked evidence under database constraints: resolved intervals require a positive tick and non-UNRESOLVED source; blockers require no tick and a reason.
- Keep inventory idempotent through exact interval/status lookup before persistence.
- Start with depth snapshots because they retain token identity. Price-snapshot coverage needs a trustworthy historical market-to-token mapping before it can be resolved or blocked per token.

## Tests run

GitHub Actions checkpoint run `30559262940` on head `442713b2a6f97a8eddae3b9293a40ea460308379`.

- `HistoricalTickCoverageServiceTest`: 3 passed.
- Clean PostgreSQL migration including V24: passed.
- Python tests: passed.
- Angular tests and production build: passed.
- Static repository/dependency checks: passed.
- Secret scan: passed.
- Java-plus-executor compose smoke: passed.
- Full Java suite: 287 tests, 7 failures, 18 errors, 2 skipped.
  - All T053-specific tests pass.
  - Remaining failures/errors are the established architecture, runtime-state, and order-lifecycle baseline.

## Safety impact

This task changes historical replay-data provenance and validation only. It does not enable live capital or change live strategy thresholds, risk, routing, cancellation, reconciliation, settlement, or fee semantics. The implementation introduces no `0.01` fallback and explicitly states that price levels cannot establish tick authority.

## Backward compatibility

- V24 is additive.
- Existing tick metadata and replay-time lookup behavior are unchanged.
- Existing snapshots are not modified or assigned invented metadata.
- Inventory writes only new blocker/provenance rows.

## Remaining risks

- Price snapshots lack token IDs, so market/token identity must be sourced before complete price-snapshot coverage can be represented.
- A documented evidence import path and resolved-interval replacement/supersession semantics remain to be implemented.
- Replay entry points still need to consult coverage and reject blocked intervals explicitly.

## Follow-up tasks

None identified yet; remaining work is within T053.

## Completion checklist

- [ ] Acceptance criteria met
- [x] Tests added/updated
- [x] Checkpoint tests run and results recorded
- [x] Task section status updated
- [x] Task index updated
- [x] No unrelated strategy tuning included
