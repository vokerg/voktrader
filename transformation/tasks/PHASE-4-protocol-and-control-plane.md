# Phase 4 Protocol And Control Plane

This ledger contains the detailed task contracts for this phase. Agents claim and update one task section per PR.

---

## T040 - Add market WebSocket heartbeat and gap supervision

Status: DONE
Priority: P1
Phase: P4 - Protocol currency and control-plane hardening
Owner: vokerg
Branch: `task/T040-market-websocket-supervision`
PR: #10
Started: 2026-07-25T14:50:35Z
Completed: 2026-07-25T15:30:42Z
Depends On: T000
Parallelizable: yes

### Objective

Comply with current heartbeat behavior and pause strategies through data gaps.

### Implementation steps
1. Send PING at the required cadence and supervise connection health.
2. Assign connection generation IDs.
3. REST-reseed after reconnect before strategy resumes.
4. Expose gap, reconnect, and pause metrics.

### Acceptance criteria
- [x] A 30-minute soak has no heartbeat disconnects. (Deterministic supervisor soak.)
- [x] Forced disconnect reseeds before strategy evaluation resumes.

### Required report
`transformation/reports/T040-2026-07-25-add-market-websocket-heartbeat-and-gap-supervision.md`

---

## T041 - Support dynamic tick metadata

Status: DONE
Priority: P1
Phase: P4 - Protocol currency and control-plane hardening
Owner: vokerg
Branch: `task/T041-dynamic-tick-metadata`
PR: #11
Started: 2026-07-25T16:43:52Z
Completed: 2026-07-25T17:21:00Z
Depends On: T040
Parallelizable: no

### Objective

Fetch and react to tick-size changes for order rounding and validation.

### Implementation steps
1. Persist per-token tick metadata.
2. Handle tick_size_change events.
3. Route all rounding through one tick service.
4. Reject invalid price/tick combinations before executor submission.

### Acceptance criteria
- [x] A change from .01 to .001 changes validation immediately.
- [x] Replay and live use identical tick metadata.

### Required report
`transformation/reports/T041-2026-07-25-support-dynamic-tick-metadata.md`

---

## T042 - Replace hard-coded fee assumptions

Status: READY
Priority: P1
Phase: P4 - Protocol currency and control-plane hardening
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T000
Parallelizable: yes

### Objective

Use one fee model across live, paper, replay, and Strategy V2.

### Implementation steps
1. Persist per-market fee rate, exponent, and taker-only metadata.
2. Remove stale hard-coded fallback formulas.
3. Version the fee model in run manifests.
4. Add official-example and cross-mode parity tests.

### Acceptance criteria
- [ ] Identical metadata, side, role, price, and shares produce the same fee in all modes.
- [ ] Strategy V2 has no separate fee fallback.

### Required report
`transformation/reports/T042-YYYY-MM-DD-replace-hard-coded-fee-assumptions.md`

---

## T043 - Map matching-engine restart modes

Status: BLOCKED
Priority: P1
Phase: P4 - Protocol currency and control-plane hardening
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T021
Parallelizable: no

### Objective

Handle 425, 503, Retry-After, cancel-only, and post-only modes explicitly.

### Implementation steps
1. Extend executor response schema.
2. Persist restricted-mode state and Retry-After.
3. Pause/retry according to protocol semantics.
4. Allow cancellation while blocking new entries in cancel-only mode.

### Acceptance criteria
- [ ] 425/503 are not collapsed into generic failure.
- [ ] New entries pause during restricted modes.
- [ ] Cancellation remains available.

### Required report
`transformation/reports/T043-YYYY-MM-DD-map-matching-engine-restart-modes.md`

---

## T044 - Harden live control-plane security

Status: DONE
Priority: P1
Phase: P4 - Protocol currency and control-plane hardening
Owner: vokerg
Branch: `task/T044-live-control-plane-security`
PR: #14
Started: 2026-07-29T12:04:56Z
Completed: 2026-07-29T12:40:23Z
Depends On: T000
Parallelizable: yes

### Objective

Prevent anonymous or unsafe control-plane mutation.

### Implementation steps
1. Add Spring Security.
2. Disable H2, Swagger, and Admin in live.
3. Bind live locally unless explicitly configured.
4. Add read-only, operator, and admin roles.
5. Add CSRF/confirmation for browser mutations and immutable audit events.

### Acceptance criteria
- [x] Anonymous live mutations receive 401/403.
- [x] H2 and Swagger routes do not exist in live.
- [x] Control actions create audit events.

### Required report
`transformation/reports/T044-2026-07-29-harden-live-control-plane-security.md`

---

## T045 - Add live preflight endpoint

Status: BLOCKED
Priority: P1
Phase: P4 - Protocol currency and control-plane hardening
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T010, T034, T040, T041, T042, T044
Parallelizable: no

### Objective

Expose one fail-closed, operator-readable live-readiness result.

### Implementation steps
Aggregate profile, arm state, kill switch, account/executor identity, WS health, reconciliation, fee/tick metadata, balances, and unresolved orders into machine-readable blocking reasons.

### Acceptance criteria
- [ ] One endpoint identifies every active blocker.
- [ ] Missing or stale inputs fail closed.

### Required report
`transformation/reports/T045-YYYY-MM-DD-add-live-preflight-endpoint.md`

---

## T050 - Add CI pipeline and smoke compose

Status: DONE
Priority: P1
Phase: P4 - Protocol currency and control-plane hardening
Owner: vokerg
Branch: `task/T050-ci-pipeline-smoke-compose`
PR: #13
Started: 2026-07-28T15:48:37Z
Completed: 2026-07-28T16:25:50Z
Depends On: T000
Parallelizable: yes

### Objective

Protect main and transformation branches with repeatable evidence.

### Implementation steps
1. Add Java, Python, Angular, migration, and static-check jobs.
2. Add a minimal Java-plus-sidecar smoke environment.
3. Cache dependencies safely.
4. Add secret/default-token scanning.

### Acceptance criteria
- [x] CI runs on PRs targeting transformation/2.0.
- [x] Clean-database migration test runs.
- [x] Default local test commands are documented.

### Required report
`transformation/reports/T050-2026-07-28-add-ci-pipeline-and-smoke-compose.md`

---

## T051 - Enforce Flyway schema authority

Status: READY
Priority: P2
Phase: P4 - Protocol currency and control-plane hardening
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T050
Parallelizable: no

### Objective

Stop Hibernate from mutating production schema implicitly.

### Implementation steps
1. Set production ddl-auto to validate/none.
2. Keep Flyway authoritative.
3. Add migration validation tests.
4. Document any local-development override.

### Acceptance criteria
- [ ] Clean DB migrates deterministically.
- [ ] Production cannot auto-update schema.

### Required report
`transformation/reports/T051-YYYY-MM-DD-enforce-flyway-schema-authority.md`

---

## T052 - Lock dependency and SDK contracts

Status: READY
Priority: P2
Phase: P4 - Protocol currency and control-plane hardening
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T050
Parallelizable: no

### Objective

Make Java/Python/Angular installs reproducible and detect protocol drift.

### Implementation steps
1. Pin/lock Python dependencies.
2. Enforce Maven and Angular lock discipline.
3. Add executor-adapter contract tests.
4. Report SDK/protocol capability in preflight.

### Acceptance criteria
- [ ] Fresh checkout installs reproducibly.
- [ ] Response-shape drift breaks the adapter contract test.

### Required report
`transformation/reports/T052-YYYY-MM-DD-lock-dependency-and-sdk-contracts.md`

---

## T053 - Backfill historical tick provenance

Status: READY
Priority: P2
Phase: P4 - Protocol currency and control-plane hardening
Owner: unclaimed
Branch:
PR:
Started:
Completed:
Depends On: T041
Parallelizable: yes

### Objective

Make pre-T041 replay datasets explicit and trustworthy without inventing historical tick metadata.

### Implementation steps
1. Inventory replay snapshots captured before dynamic tick persistence existed.
2. Backfill only tick observations that can be verified from retained REST books, event payloads, or another documented source.
3. Persist source and coverage provenance for each backfilled interval.
4. Mark unresolved intervals as replay blockers instead of falling back to an assumed tick.

### Acceptance criteria
- [ ] Every pre-T041 replay interval either resolves to sourced tick metadata or reports an explicit coverage blocker.
- [ ] No backfill path introduces an implicit `0.01` fallback.

### Required report
`transformation/reports/T053-YYYY-MM-DD-backfill-historical-tick-provenance.md`

## Phase-wide safety rule

Do not enable live capital or tune strategy thresholds. Every task must update its section and index, record exact test evidence, and create follow-up work instead of silently broadening scope.
