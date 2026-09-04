# Phase 7 Robustness, Shadow, and Paper

This ledger contains the detailed task contracts for this phase. Agents claim and update one task section per PR.

---

## T090 - Implement shadow execution mode

Status: BLOCKED
Priority: P1
Phase: P7 - Robustness, shadow, and paper
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T074, T082
Parallelizable: no

### Objective
Generate live decisions without sending orders and evaluate them against actual subsequent data.

### Implementation steps
1. Route accepted intents to a shadow ledger instead of the executor.
2. Record would-submit time, price, quantity, risk result, and executable-EV components.
3. Evaluate subsequent book/user events for hypothetical fills and outcomes.
4. Report slippage, missed fills, opportunity duration, and adverse selection.

### Acceptance criteria
- [ ] Shadow mode never calls executor submit.
- [ ] Shadow decisions can be replayed and scored from persisted events.
- [ ] Shadow records contain the same decision evidence as live intents.

### Required report
`transformation/reports/T090-YYYY-MM-DD-implement-shadow-execution-mode.md`

---

## T091 - Add chaos and restart suite

Status: BLOCKED
Priority: P1
Phase: P7 - Robustness, shadow, and paper
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T023, T031, T034, T062
Parallelizable: no

### Objective
Prove state convergence under process, network, database, and data-source failures.

### Implementation steps
1. Add fault injection for JVM crash, sidecar crash, DB interruption, timeout, delayed user WS, partial fill, cancellation race, and market-feed reconnect.
2. Run against a deterministic fake executor/exchange harness.
3. Assert local/remote order uniqueness and ledger convergence after restart.
4. Record unresolved/manual-review cases explicitly.

### Acceptance criteria
- [ ] Chaos suite passes before paper promotion.
- [ ] No scenario creates duplicate remote orders.
- [ ] Every ambiguous scenario converges or enters explicit manual review.

### Required report
`transformation/reports/T091-YYYY-MM-DD-add-chaos-and-restart-suite.md`

---

## T092 - Run paper promotion trial

Status: BLOCKED
Priority: P1
Phase: P7 - Robustness, shadow, and paper
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T090, T091
Parallelizable: no

### Objective
Exercise the full path without capital and compare model expectations to observed paper/shadow outcomes.

### Implementation steps
1. Freeze market families, strategy config, model versions, fee/tick metadata, and evaluation thresholds.
2. Run for a predeclared minimum count of independent opportunities.
3. Report calibration, simulator-vs-observed fill behavior, latency, adverse selection, missed opportunities, and rejection reasons.
4. Do not tune during the trial.

### Acceptance criteria
- [ ] Trial report meets predeclared promotion thresholds.
- [ ] Any breach creates a follow-up task before live promotion.
- [ ] Results are stored in the experiment registry.

### Required report
`transformation/reports/T092-YYYY-MM-DD-run-paper-promotion-trial.md`

---

## T093 - Build operator evidence dashboards

Status: BLOCKED
Priority: P2
Phase: P7 - Robustness, shadow, and paper
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T015, T045, T082
Parallelizable: no

### Objective
Support safety and evidence review without adding speculative UI.

### Implementation steps
1. Show can-trade state, arm expiry, kill switch, market/user WS health, unresolved orders, and provisional/settled positions.
2. Show why-did-it-trade evidence: q, executable EV, risk gates, feature snapshot, and model/config versions.
3. Show research comparability: code, config, data, fee, tick, and simulator versions.
4. Add a manual-review queue for mismatches and unknown outcomes.

### Acceptance criteria
- [ ] Operator can answer: can it trade, why did it trade, what is true now, and are results comparable?
- [ ] Mutation actions are authenticated and audited.

### Required report
`transformation/reports/T093-YYYY-MM-DD-build-operator-evidence-dashboards.md`

## Phase-wide protocol

Each task must update its task metadata and the task index, record exact test commands and results, and create explicit follow-up tasks instead of broadening scope silently. No real live trading is enabled in this phase.
