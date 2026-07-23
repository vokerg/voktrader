# Phase 6 Fair Value And Strategy

This ledger contains the detailed task contracts for this phase. Agents claim and update one task section per PR.

---

## T070 - Capture underlying venue composite inputs

Status: BLOCKED
Priority: P1
Phase: P6 - Fair-value layer and strategy simplification
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T061
Parallelizable: no

### Objective
Record exogenous underlying data required for fair-value estimates.

### Implementation steps
1. Integrate at least two venue feeds per asset.
2. Store bid/ask/trade, source time, receive time, venue, and quality flags.
3. Build a deterministic composite with stale/outlier handling.
4. Expose source and receive latency metrics.

### Acceptance criteria
- [ ] Composite can be reconstructed from stored events.
- [ ] Stale/outlier venues are excluded deterministically.

### Required report
`transformation/reports/T070-YYYY-MM-DD-capture-underlying-venue-composite-inputs.md`

---

## T071 - Persist contract reference and settlement metadata

Status: BLOCKED
Priority: P1
Phase: P6 - Fair-value layer and strategy simplification
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T061
Parallelizable: no

### Objective
Store price-to-beat, horizon, and settlement/oracle mechanics for every market.

### Implementation steps
1. Extract K/price-to-beat and market-family metadata.
2. Persist start/end times, settlement source/rule, asset, and horizon.
3. Validate consistency against slug/question data.
4. Make missing metadata a backtest/live blocker.

### Acceptance criteria
- [ ] Every promoted market has K, horizon, asset, and settlement metadata.
- [ ] Run manifests record the metadata version.

### Required report
`transformation/reports/T071-YYYY-MM-DD-persist-contract-reference-and-settlement-metadata.md`

---

## T072 - Implement baseline q probability model

Status: BLOCKED
Priority: P1
Phase: P6 - Fair-value layer and strategy simplification
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T070, T071
Parallelizable: no

### Objective
Create a transparent, falsifiable fair-value baseline.

### Implementation steps
1. Estimate q = P(settlement true) from the underlying composite, K, horizon, volatility, and explicit basis/oracle adjustments.
2. Version the model and feature-vector hash.
3. Produce q_raw, q_calibrated, and uncertainty outputs.
4. Keep the model independent from Polymarket book price.

### Acceptance criteria
- [ ] q is computed identically in live and replay.
- [ ] Reliability metrics can evaluate q against outcomes.
- [ ] No feature is called edge unless based on independent q.

### Required report
`transformation/reports/T072-YYYY-MM-DD-implement-baseline-q-probability-model.md`

---

## T073 - Add calibration and forecast diagnostics

Status: BLOCKED
Priority: P1
Phase: P6 - Fair-value layer and strategy simplification
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T072
Parallelizable: no

### Objective
Measure whether fair-value probabilities are statistically meaningful.

### Implementation steps
1. Compute Brier score, log loss, calibration intercept/slope, reliability bins, and realized frequency by q bin.
2. Split by asset, horizon, and regime.
3. Persist run artifacts and operator/report data.
4. Fail promotion when calibration breaches the declared band.

### Acceptance criteria
- [ ] Diagnostics exist for every candidate run.
- [ ] Holdout calibration is visible and reproducible.

### Required report
`transformation/reports/T073-YYYY-MM-DD-add-calibration-and-forecast-diagnostics.md`

---

## T074 - Implement executable EV gate

Status: BLOCKED
Priority: P1
Phase: P6 - Fair-value layer and strategy simplification
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T072, T063, T042
Parallelizable: no

### Objective
Trade only when independent q exceeds executable cost under uncertainty.

### Implementation steps
1. Compute book-walk average and worst prices.
2. Subtract fees, latency/slippage reserve, and uncertainty buffer.
3. Expose every EV component in the decision reason.
4. Require positive lower-bound EV for entry and promotion.

### Acceptance criteria
- [ ] No entry is accepted without positive executable EV under the configured buffer.
- [ ] Decision evidence includes q, price, fee, slippage, latency reserve, and uncertainty.

### Required report
`transformation/reports/T074-YYYY-MM-DD-implement-executable-ev-gate.md`

---

## T080 - Simplify Strategy V2 active set

Status: BLOCKED
Priority: P2
Phase: P6 - Fair-value layer and strategy simplification
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T074
Parallelizable: no

### Objective
Reduce strategy surface to one taker baseline plus a disabled maker candidate.

### Implementation steps
1. Archive current YAML variants as pre-2.0 research artifacts.
2. Create one fair-value taker configuration.
3. Keep maker disabled until user WS, dead-man, and queue calibration are proven.
4. Remove or rename misleading `mid_edge` semantics.

### Acceptance criteria
- [ ] Default active set contains one promoted candidate.
- [ ] Old configurations are marked non-comparable and cannot be promoted accidentally.

### Required report
`transformation/reports/T080-YYYY-MM-DD-simplify-strategy-v2-active-set.md`

---

## T081 - Retire legacy hard-coded strategies

Status: BLOCKED
Priority: P2
Phase: P6 - Fair-value layer and strategy simplification
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T080, T062
Parallelizable: no

### Objective
Delete or quarantine non-Strategy-V2 implementations after parity and migration.

### Implementation steps
1. Inventory legacy strategy classes, support code, and property groups.
2. Retain only regression fixtures or compatibility adapters with an explicit removal date.
3. Remove legacy defaults/configuration surface.
4. Update strategy catalog and runbook.

### Acceptance criteria
- [ ] Runtime default exposes Strategy V2 only.
- [ ] Removal does not break replay/parity tests.

### Required report
`transformation/reports/T081-YYYY-MM-DD-retire-legacy-hard-coded-strategies.md`

---

## T082 - Create experiment registry

Status: BLOCKED
Priority: P2
Phase: P6 - Fair-value layer and strategy simplification
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T064, T073
Parallelizable: no

### Objective
Make every research, shadow, and paper run an immutable scientific object.

### Implementation steps
1. Persist run ID, code SHA, dirty-tree flag, config/data hashes, model/fee/tick/simulator versions, metrics, and artifacts.
2. Add APIs/CLI to list and compare compatible runs.
3. Prevent overwriting completed manifests.

### Acceptance criteria
- [ ] Every backtest/shadow/paper run has an immutable manifest.
- [ ] Results cannot be compared as equivalent without compatible versions.

### Required report
`transformation/reports/T082-YYYY-MM-DD-create-experiment-registry.md`

## Phase-wide safety rule

Do not promote endogenous price movement as statistical edge. Every task updates its section/index/report and creates explicit follow-up work when model or data evidence invalidates the plan.
