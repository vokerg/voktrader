# Phase 5 Simulation Honesty

This ledger contains the detailed task contracts for this phase. Agents claim and update one task section per PR.

---

## T060 - Replay exact recorded depth levels

Status: READY
Priority: P0
Phase: P5 - Simulation honesty
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T050
Parallelizable: no

### Objective
Replace synthetic one-level replay books with persisted full-depth levels.

### Implementation steps
1. Load `market_depth_snapshot_levels` by side and levelIndex.
2. Build OutcomeOrderBook from exact rows.
3. Assert summary best bid/ask/depth matches level-derived values.
4. Reject incomplete/corrupt ticks and report coverage.

### Acceptance criteria
- [ ] Golden-tick replayed book equals persisted levels.
- [ ] Depth-aware features match the live captured feature vector.

### Required report
`transformation/reports/T060-YYYY-MM-DD-replay-exact-recorded-depth-levels.md`

---

## T061 - Persist normalized event log

Status: BLOCKED
Priority: P1
Phase: P5 - Simulation honesty
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T040, T030
Parallelizable: no

### Objective
Create a deterministic event dataset across market, user, and underlying feeds.

### Implementation steps
1. Persist source time, receive time, connection generation, event type, raw payload, and normalized payload.
2. Capture REST reseed boundaries.
3. Capture user order/fill events.
4. Define deterministic event ordering and tie-breakers.

### Acceptance criteria
- [ ] Replay can sort by deterministic event order.
- [ ] Missing/gapped events are reported rather than silently skipped.

### Required report
`transformation/reports/T061-YYYY-MM-DD-persist-normalized-event-log.md`

---

## T062 - Build live/replay parity tests

Status: BLOCKED
Priority: P1
Phase: P5 - Simulation honesty
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T060, T061, T012, T042, T041
Parallelizable: no

### Objective
Prove identical event input creates identical features, risk decisions, fees, tick rounding, and intents.

### Implementation steps
1. Create a stable golden-market fixture.
2. Run live-style and replay paths over the same input.
3. Compare normalized outputs and reasons.
4. Add regression snapshots with explicit versioning.

### Acceptance criteria
- [ ] Test fails on feature, risk, fee, tick, or intent divergence.
- [ ] Fixture provenance and expected outputs are documented.

### Required report
`transformation/reports/T062-YYYY-MM-DD-build-live-replay-parity-tests.md`

---

## T063 - Implement calibrated execution simulator

Status: BLOCKED
Priority: P1
Phase: P5 - Simulation honesty
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T060, T031
Parallelizable: no

### Objective
Model taker book-walk, FOK/FAK, maker queue assumptions, partial fills, cancellation, latency, and settlement.

### Implementation steps
1. Implement full-depth taker book-walk.
2. Represent FOK reject, FAK partial, and GTC/GTD maker models.
3. Parameterize submit/cancel latency and queue assumptions.
4. Version simulator and assumptions in every run manifest.

### Acceptance criteria
- [ ] Paper/shadow/live-observed fills fall inside declared simulator bands.
- [ ] Every backtest reports its fill, queue, latency, and settlement assumptions.

### Required report
`transformation/reports/T063-YYYY-MM-DD-implement-calibrated-execution-simulator.md`

---

## T064 - Separate operational and analytical stores

Status: BLOCKED
Priority: P2
Phase: P5 - Simulation honesty
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T051, T061
Parallelizable: no

### Objective
Prevent backtests and large replays from contaminating operational state.

### Implementation steps
1. Choose a dedicated analytical schema/store such as Parquet/DuckDB or isolated Postgres.
2. Keep live operational state in Postgres.
3. Add immutable run manifests and artifacts.
4. Make live capacity queries unable to see backtest rows by construction.

### Acceptance criteria
- [ ] Backtest rows cannot affect live exposure queries.
- [ ] A run is reproducible from its manifest and dataset hash.

### Required report
`transformation/reports/T064-YYYY-MM-DD-separate-operational-and-analytical-stores.md`

## Phase-wide safety rule

Do not tune strategies against pre-fix replay results. Archive old PnL as non-comparable, update task/index/report metadata, and create follow-up tasks instead of hiding data-quality gaps.
