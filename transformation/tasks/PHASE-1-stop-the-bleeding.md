# Phase 1 Stop The Bleeding

This ledger contains the detailed task contracts for this phase. Agents claim and update one task section per PR.

The 2026-08-03 checkpoint added mandatory integration gate T016 after T012. T016, T013, and T014 are now DONE; T015 is READY. See `CHECKPOINT-2026-08-03.md`.

---

## T010 - Make live profile capability-only

Status: DONE
Priority: P0
Phase: P1 - Stop the bleeding
Owner: openai-gpt-5.6-thinking
Branch: task/T010-live-profile-capability-only
PR: #7
Started: 2026-07-24T05:38:12Z
Completed: 2026-07-24T06:16:00Z
Depends On: T000
Parallelizable: no

## Objective

Separate live capability from live arming so starting the live profile cannot submit real orders by itself.

## Context

Read `transformation/MASTER_PLAN.md` and `AGENTS.md` before implementation. Keep scope narrow and update this task section plus `transformation/tasks/INDEX.md` in the task PR.

## Implementation steps

1. Change live defaults so kill switch remains on.
2. Require explicit arm state with expiry for live submissions.
3. Validate non-default executor token and expected account metadata during startup/preflight.
4. Expose arming state in runtime status.

## Acceptance criteria

- [x] Live-capable profile starts in unarmed state.
- [x] Submitting a live entry while unarmed is rejected before executor submit.
- [x] Exit and cancel remain available for existing live orders/positions.

## Required tests

- Add unit and integration tests for entry blocking and exit/cancel permissiveness.
- Record exact commands and results in `transformation/reports/T010-YYYY-MM-DD-make-live-profile-capability-only.md`.

## Completion update protocol

Change status, fill ownership/branch/PR/timestamps, update the index, add the report, and create/reorder follow-up tasks when evidence requires it.

---

## T011 - Introduce typed entry and exit intent boundary

Status: DONE
Priority: P0
Phase: P1 - Stop the bleeding
Owner: openai-gpt-5.6-thinking
Branch: task/T011-typed-entry-exit-intents
PR: #8
Started: 2026-07-24T18:16:52Z
Completed: 2026-07-24T18:41:51Z
Depends On: T010
Parallelizable: no

## Objective

Replace generic strategy-to-execution plumbing with explicit EntryIntent and ExitIntent boundaries.

## Implementation steps

1. Create entry acceptance service API.
2. Create exit submission API or separate reducer gateway.
3. Migrate Strategy V2 to emit only typed intent.
4. Keep legacy strategies routed through compatibility adapters temporarily.

## Acceptance criteria

- [x] No strategy class directly selects live order gateway or executor route.
- [x] Entry and exit APIs make bypass harder by type design.
- [x] Compiler prevents Strategy V2 from directly calling Python executor.

## Required tests

Add architecture/compilation tests and route tests. Record results in `transformation/reports/T011-YYYY-MM-DD-introduce-typed-entry-and-exit-intent-boundary.md`.

## Completion update protocol

Change status, fill ownership/branch/PR/timestamps, update the index, add the report, and create/reorder follow-up tasks when evidence requires it.

---

## T012 - Centralize entry risk policy

Status: DONE
Priority: P0
Phase: P1 - Stop the bleeding
Owner: vokerg-gpt-5.6-thinking-20260804
Branch: task/T012-central-risk-reconstruction
PR: #26
Started: 2026-08-04T17:41:49Z
Completed: 2026-08-04T17:56:00Z
Depends On: T011
Parallelizable: no

## Objective

Move RiskCheckService semantics behind the sole entry boundary and make all new-position entries use it.

## Checkpoint constraint

PR #26 reconstructs the useful parts of closed stale PR #9 on the current transformation head. The reconstruction reconciles the current migration chain, restores the T011 architecture contract, publishes exact Java failure changes, and unblocks only T016.

## Implementation steps

1. Refactor RiskCheckService into `assessEntry` with typed inputs.
2. Persist risk checks before an accepted intent enters the outbox.
3. Add a risk result object distinguishing blocking, warning, and informational checks.
4. Ensure paper, replay, shadow, and live all call the same policy.
5. Restore the T011 typed strategy architecture on the integrated head.
6. Record exact before/after Java failure identities and counts.

## Acceptance criteria

- [x] Every BUY/new-position path calls central risk policy exactly once.
- [x] Risk checks are saved with intent/order correlation IDs.
- [x] Mutation removing the risk call fails tests.
- [x] SELL and cancel remain available outside new-exposure gating.
- [x] Complete CI runs on the current integrated head without adding a new failure.
- [x] T016 is the only downstream task unblocked directly by T012.

Implementation report: `transformation/reports/T012-2026-08-04-centralize-entry-risk-policy.md`

## Required tests

Add exhaustive route tests, mutation-equivalent negative coverage, architecture coverage, and full-CI evidence. Record results in `transformation/reports/T012-YYYY-MM-DD-centralize-entry-risk-policy.md`.

## Completion update protocol

Change status, fill ownership/branch/PR/timestamps, update the index, add the report, and create/reorder follow-up tasks when evidence requires it.

---

## T013 - Define portfolio exposure invariants

Status: DONE
Priority: P0
Phase: P1 - Stop the bleeding
Owner: vokerg-gpt-5.6-thinking-20260804
Branch: task/T013-portfolio-exposure-invariants
PR: #28
Started: 2026-08-04T18:27:56Z
Completed: 2026-08-04T18:47:00Z
Depends On: T016
Parallelizable: no

## Objective

Separate Strategy V2 inner-strategy ownership from portfolio exposure limits.

## Implementation steps

1. Introduce PortfolioSnapshot keyed by bot, account, market, token/outcome, mode, and settlement state.
2. Define config for one-position-per-bot-market, one-position-per-token, and portfolio-level caps.
3. Stop using inner strategy ID as the only duplicate-position key.
4. Document semantics in runtime status.

## Acceptance criteria

- [x] With one open/provisional position in a market, other inner strategies are blocked according to configured portfolio mode.
- [x] Closed trades do not incorrectly count as active exposure.
- [x] Cumulative attempt caps and active caps are separately named.

Implementation report: `transformation/reports/T013-2026-08-04-define-portfolio-exposure-invariants.md`

## Required tests

Add cross-inner-strategy and settled/provisional exposure tests. Record results in `transformation/reports/T013-YYYY-MM-DD-define-portfolio-exposure-invariants.md`.

## Completion update protocol

Change status, fill ownership/branch/PR/timestamps, update the index, add the report, and create/reorder follow-up tasks when evidence requires it.

---

## T014 - Prove kill switch covers every live entry route

Status: DONE
Priority: P0
Phase: P1 - Stop the bleeding
Owner: vokerg-gpt-5.6-thinking-20260804
Branch: task/T014-kill-switch-route-proof
PR: #29
Started: 2026-08-04T18:52:14Z
Completed: 2026-08-04T19:15:00Z
Depends On: T016, T013
Parallelizable: no

## Objective

Build an exhaustive test harness that proves kill switch prevents executor submission from every entry route.

## Implementation steps

1. Enumerate Strategy V2 order-layer path, Strategy V2 router path, legacy strategy path, API/manual entry path if present, and outbox worker path.
2. Mock PythonExecutorClient and assert no submit call.
3. Run with LIVE mode, live-enabled true, kill-switch true.
4. Add a mutation-test-like negative fixture or contract test.

## Acceptance criteria

- [x] Kill switch test fails if any route bypasses central risk.
- [x] Exit and cancel tests prove risk-reducing actions remain allowed.

Implementation report: `transformation/reports/T014-2026-08-04-prove-kill-switch-covers-every-live-entry-route.md`

## Required tests

Record exact route matrix and results in `transformation/reports/T014-YYYY-MM-DD-prove-kill-switch-covers-every-live-entry-route.md`.

## Completion update protocol

Change status, fill ownership/branch/PR/timestamps, update the index, add the report, and create/reorder follow-up tasks when evidence requires it.

---

## T015 - Expose effective risk gate chain

Status: READY
Priority: P0
Phase: P1 - Stop the bleeding
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T016
Parallelizable: no

## Objective

Make startup/runtime status tell the operator which risk gates are active and which path will execute entries.

## Implementation steps

1. Add RiskGateStatus DTO.
2. Include kill switch, live enabled, arm state, account preflight, portfolio policy, outbox state, user WS, and executor identity.
3. Add endpoint or extend runtime status.
4. Add tests for live-capable and paper profiles.

## Acceptance criteria

- [ ] Operator can see why live trading is or is not possible.
- [ ] Status shows missing/disabled gate as blocking.

## Required tests

Record profile/status contract results in `transformation/reports/T015-YYYY-MM-DD-expose-effective-risk-gate-chain.md`.

## Completion update protocol

Change status, fill ownership/branch/PR/timestamps, update the index, add the report, and create/reorder follow-up tasks when evidence requires it.
