# Phase 3 Exchange Truth

This ledger contains the detailed task contracts for this phase. Agents claim and update one task section per PR.

The 2026-08-03 checkpoint requires durable lifecycle phase-exit gate T026 before authenticated exchange-truth work begins. See `CHECKPOINT-2026-08-03.md`.

---

## T030 - Add authenticated user WebSocket consumer

Status: READY
Priority: P0
Phase: P3 - Exchange truth and settlement ledger
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T026
Parallelizable: no

### Objective
Consume user order/trade lifecycle events as primary exchange truth.

### Implementation steps
1. Extend the sidecar or Java adapter for the authenticated user channel.
2. Authenticate without exposing credentials.
3. Deduplicate by remote event, order, and trade IDs.
4. Persist raw and normalized events.
5. Expose user-WS health and connection generation.

### Acceptance criteria
- [ ] User-WS events update local state within one processing cycle.
- [ ] A dropped connection blocks arming when live orders or provisional fills exist.
- [ ] REST is reconciliation, not the primary lifecycle source.

### Required report
`transformation/reports/T030-YYYY-MM-DD-add-authenticated-user-websocket-consumer.md`

---

## T031 - Implement provisional settlement state machine

Status: BLOCKED
Priority: P0
Phase: P3 - Exchange truth and settlement ledger
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T030
Parallelizable: no

### Objective
Represent MATCHED, MINED, CONFIRMED, RETRYING, and FAILED explicitly.

### Implementation steps
1. Add provisional and settled fill states/entities.
2. Expose provisionalShares and settledShares.
3. Permit only CONFIRMED to update settled inventory and realized PnL.
4. Reverse provisional inventory and fees deterministically on FAILED.

### Acceptance criteria
- [ ] MATCHED -> FAILED leaves no settled position.
- [ ] MATCHED -> MINED -> CONFIRMED promotes exactly once.
- [ ] Restart between every event yields the same ledger.

### Required report
`transformation/reports/T031-YYYY-MM-DD-implement-provisional-settlement-state-machine.md`

---

## T032 - Split settled and provisional ledger queries

Status: BLOCKED
Priority: P0
Phase: P3 - Exchange truth and settlement ledger
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T031
Parallelizable: no

### Objective
Prevent strategies and dashboards from treating provisional inventory as settled truth.

### Implementation steps
1. Add ledger queries for settled position, provisional position, and combined exposure.
2. Default normal exits to settled shares unless an explicit emergency/reconciliation mode applies.
3. Update operator DTOs and labels.
4. Cover provisional-only, settled, partially confirmed, and failed cases.

### Acceptance criteria
- [ ] PnL uses settled inventory by default.
- [ ] Portfolio risk sees provisional exposure to prevent duplicate entries.
- [ ] Operator UI/status distinguishes provisional and settled quantities.

### Required report
`transformation/reports/T032-YYYY-MM-DD-split-settled-and-provisional-ledger-queries.md`

---

## T033 - Remove ambiguous fill association

Status: BLOCKED
Priority: P1
Phase: P3 - Exchange truth and settlement ledger
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T030, T031
Parallelizable: no

### Objective
Stop attaching fills through broad token/side/price/share matching when identical orders overlap.

### Implementation steps
1. Prefer exact remote order/trade IDs.
2. If an exact ID is unavailable, require a unique bounded candidate.
3. Otherwise enter MANUAL_REVIEW.
4. Test two identical overlapping orders.

### Acceptance criteria
- [ ] Two identical orders never share a fill.
- [ ] Ambiguity enters manual review and blocks unsafe exposure assumptions.

### Required report
`transformation/reports/T033-YYYY-MM-DD-remove-ambiguous-fill-association.md`

---

## T034 - Build startup reconciliation preflight

Status: BLOCKED
Priority: P0
Phase: P3 - Exchange truth and settlement ledger
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T030, T031, T023
Parallelizable: no

### Objective
Refuse live arming until local and remote truth converge.

### Implementation steps
1. Fetch open orders, recent trades/fills, balances, and account/funder/chain identity.
2. Compare them with local nonterminal orders and provisional positions.
3. Resolve known terminal states automatically.
4. Surface unresolved mismatch in preflight and runtime status.

### Acceptance criteria
- [ ] App refuses to arm with unresolved remote/local mismatch.
- [ ] A clean account passes preflight.
- [ ] The operator receives a detailed, auditable preflight report.

### Required report
`transformation/reports/T034-YYYY-MM-DD-build-startup-reconciliation-preflight.md`

---

## T035 - Implement exchange dead-man heartbeat

Status: BLOCKED
Priority: P1
Phase: P3 - Exchange truth and settlement ledger
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T030, T025
Parallelizable: no

### Objective
Use exchange-side auto-cancel controls for resting live orders.

### Implementation steps
1. Add heartbeat sender and renewal schedule.
2. Tie dead-man scope to active resting orders.
3. Stop/delay heartbeat in a contract test to prove cancellation behavior.
4. Expose heartbeat freshness and protection scope.

### Acceptance criteria
- [ ] Resting live orders have active dead-man protection.
- [ ] Process death or heartbeat expiry cancels orders within the configured window.

### Required report
`transformation/reports/T035-YYYY-MM-DD-implement-exchange-dead-man-heartbeat.md`

## Phase-wide safety rule

Do not enable live capital or tune strategy thresholds. Each task must update its section and the task index, add exact test evidence, and create follow-up tasks instead of silently expanding scope.
