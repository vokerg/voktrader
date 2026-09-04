# Phase 8 Tiny Live Promotion

This ledger contains the detailed task contracts for this phase. Agents claim and update one task section per PR.

---

## T100 - Prepare tiny live runbook

Status: BLOCKED
Priority: P0
Phase: P8 - Tiny live promotion
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T092, T093
Parallelizable: no

### Objective
Define the exact procedure and stop conditions for one tiny live promotion.

### Implementation steps
1. Select one 15-minute market family unless evidence explicitly supports another choice.
2. Name the exact account, bot, Strategy V2 configuration, maximum order size, daily loss budget, and arm expiry.
3. Write step-by-step preflight, arm, observe, reconcile, demote, and disarm procedures.
4. Define machine-checkable stop conditions and operator signoff.
5. Rehearse the procedure in paper/shadow mode.

### Acceptance criteria
- [ ] Runbook names exact bot IDs, strategy IDs, account fingerprint, and budgets.
- [ ] Every stop condition has an owner and automated enforcement where possible.
- [ ] A dry rehearsal completes without unresolved mismatch.

### Required report
`transformation/reports/T100-YYYY-MM-DD-prepare-tiny-live-runbook.md`

---

## T101 - Execute tiny live promotion

Status: BLOCKED
Priority: P0
Phase: P8 - Tiny live promotion
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T100
Parallelizable: no

### Objective
Run the smallest live scope and collect complete execution and calibration evidence.

### Implementation steps
1. Run and archive preflight.
2. Arm manually with automatic expiry.
3. Execute only the configured account, bot, strategy, and market family.
4. Monitor reconciliation, user WS, dead-man heartbeat, market-data gaps, latency, calibration, fills, and loss budget.
5. Auto-demote on any stop-condition breach.
6. Replay every live decision from the persisted event stream.

### Acceptance criteria
- [ ] Live report includes every submitted intent, risk gate, remote lifecycle event, ledger transition, and replay comparison.
- [ ] No unresolved local/remote mismatch remains.
- [ ] Any stop-condition breach demotes the system immediately and is documented.

### Required report
`transformation/reports/T101-YYYY-MM-DD-execute-tiny-live-promotion.md`

### Safety restriction

This task requires explicit operator approval immediately before arming. An autonomous agent must not enable live capital by itself.

---

## T102 - Define expansion criteria

Status: BLOCKED
Priority: P1
Phase: P8 - Tiny live promotion
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T101
Parallelizable: no

### Objective
Decide whether and how to expand based on evidence rather than elapsed time or headline PnL.

### Implementation steps
1. Analyze independent opportunities and confirmed fills.
2. Compare predicted versus realized calibration, fill quality, fees, slippage, latency, and adverse selection.
3. Estimate drawdown, tail risk, and available capacity.
4. Decide whether to expand market family, order budget, strategy count, or none.
5. Create the next-scope task or keep live scope frozen.

### Acceptance criteria
- [ ] Expansion recommendation is backed by declared confidence bands and sufficient independent observations.
- [ ] Weak or ambiguous evidence results in no expansion.
- [ ] Any expansion is represented by a new task with its own limits and rollback criteria.

### Required report
`transformation/reports/T102-YYYY-MM-DD-define-expansion-criteria.md`

## Phase-wide protocol

Live arming requires explicit operator approval. Every task must update its task metadata and index, add an implementation report with exact evidence, and create new tasks for any scope expansion.
