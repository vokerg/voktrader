# Voktrader 2.0 Checkpoint Remediation

This ledger records the corrective work discovered by the 2026-08-03 program checkpoint. `transformation/tasks/INDEX.md` remains the ordered source of truth. These tasks are gates, not optional follow-ups.

## Checkpoint policy

- Freeze unrelated feature work while the integrated Java failure set is unchanged or worsening.
- T012 must be integrated on the current transformation head before downstream risk or lifecycle tasks start.
- T042 is promoted to P0 but follows the T016 integration checkpoint to avoid execution/Strategy V2 rework.
- Exchange-truth work starts only after the durable lifecycle phase-exit gate T026.
- Live preflight requires retained-database and control-plane assurance tasks T054 and T056.
- CI no-regression enforcement T055 may proceed in parallel immediately.

---

## T016 - Stabilize integrated execution boundary after central-risk merge

Issue: #19
Status: BLOCKED
Priority: P0
Phase: Checkpoint remediation
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T012
Parallelizable: no

### Objective

Validate central risk on the current integrated head, restore the T011 typed strategy boundary, and classify every remaining Java failure before downstream work proceeds.

### Acceptance criteria

- [ ] `StrategyExecutionBoundaryArchitectureTest` passes.
- [ ] Every new-position path crosses central risk exactly once with correlated persisted evidence.
- [ ] SELL and cancel remain available outside new-exposure gating.
- [ ] Full CI runs with fewer failures than the 301-test baseline of 7 failures and 18 errors.
- [ ] Every remaining failure is explicitly owned by T020-T025 or a newly justified task.

---

## T026 - Close durable order-lifecycle integration failures

Issue: #20
Status: BLOCKED
Priority: P0
Phase: Checkpoint remediation
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T020, T021, T022, T023, T024, T025
Parallelizable: no

### Objective

Prove the integrated order/trade lifecycle is coherent before authenticated exchange-truth and settlement work begins.

### Acceptance criteria

- [ ] `OrderManagerTest`, `OrderLifecycleIntegrationTest`, and `StrategyV2OrderLifecycleIntegrationTest` pass.
- [ ] FOK, FAK, GTC, GTD, partial fill, partial exit, cancellation, reconciliation, restart, and oversell behavior are covered.
- [ ] Immediate fills, resting orders, rejected orders, and completed exits persist correct states.
- [ ] Full Java suite has no entry/exit/order-lifecycle failures or errors.
- [ ] No remote executor submission occurs inside an acceptance transaction.

---

## T054 - Rehearse retained-database Flyway upgrade

Issue: #21
Status: READY
Priority: P0
Phase: Checkpoint remediation
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T051
Parallelizable: yes

### Objective

Prove a retained production-like database can cross the corrected Flyway history safely without destructive clean, implicit baseline, or unreviewed repair.

### Acceptance criteria

- [ ] A retained-schema fixture upgrades successfully.
- [ ] Historical checksum discrepancies are documented and explicitly approved.
- [ ] Post-upgrade Flyway and Hibernate validation pass.
- [ ] Critical row counts, constraints, and domain invariants are verified before and after.
- [ ] A repeatable retained-database rehearsal command and runbook are committed.

---

## T055 - Enforce transformation CI no-regression policy

Issue: #22
Status: IN_PROGRESS
Priority: P0
Phase: Checkpoint remediation
Owner: vokerg-gpt-5.6-thinking-20260803
Branch: task/T055-ci-no-regression
PR: pending
Started: 2026-08-03T17:37:00Z
Completed:
Depends On: T050
Parallelizable: yes

### Objective

Prevent unrelated PRs from changing or increasing the known Java failure set and make passing safety checks required where repository settings permit.

### Acceptance criteria

- [ ] A machine-readable baseline names every temporarily allowed Java failure.
- [ ] New or changed failures fail CI.
- [ ] Passing Python, Angular, static/dependency, secret, migration, and smoke jobs are required checks where supported.
- [ ] Remediation PRs publish exact before/after failure identities and counts.
- [ ] T026 completion removes the temporary baseline and makes Java hard-green required.
- [ ] No safety-critical job uses failure suppression.

---

## T056 - Prove generated Spring credentials cannot access live control plane

Issue: #23
Status: READY
Priority: P1
Phase: Checkpoint remediation
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T044
Parallelizable: yes

### Objective

Prove Spring Boot generated/default credentials and basic authentication cannot reach any live read or mutation surface.

### Acceptance criteria

- [ ] Live does not create an unnecessary generated user, or tests prove it is unreachable.
- [ ] Basic authentication cannot access live API endpoints.
- [ ] Mutations still require bearer authorization, role, separate confirmation, and successful audit persistence.
- [ ] H2, Swagger/OpenAPI, Admin, actuator, and error surfaces expose no alternate path.
- [ ] Non-live developer behavior remains explicitly scoped and tested.
