# Implementation Report

## Summary
- Implemented explicit durable handling for ambiguous executor submission outcomes.
- Classified timeout, connection reset, HTTP 425, HTTP 503, malformed response, executor crash, and other non-terminal outcomes without treating them as deterministic rejections.
- Persisted `UNKNOWN`, `RECONCILE`, and `MANUAL_REVIEW` evidence and prevented the normal dispatch worker from blindly reclaiming those states.
- Blocked new order acceptance while unresolved submission exposure exists.
- Added operator-facing status and evidence-backed resolution endpoints.
- During self-review, found and fixed a sidecar/JVM contract mismatch where an HTTP-200 `accepted=false,status=FAILED` response could have been recorded as a deterministic rejection even though the sidecar retained `UNKNOWN`.

## Task Details
- Task ID: T023
- Title: Handle unknown submission outcomes
- Phase: P2 - Durable order lifecycle
- Branch: `task/T023-handle-unknown-submission-outcomes`
- Pull request: #34
- Base branch: `transformation/2.0`
- Started: 2026-08-05T20:12:19Z
- Completed: 2026-08-06T04:28:08Z

## Files Changed
- `src/main/java/com/vokerg/voktrader/executor/ExecutorSubmissionFailureType.java`
  - Defines the typed ambiguous-submission failure taxonomy.
- `src/main/java/com/vokerg/voktrader/executor/ExecutorSubmissionException.java`
  - Carries failure type, HTTP status, response body, and causal evidence across the executor boundary.
- `src/main/java/com/vokerg/voktrader/executor/PythonExecutorClient.java`
  - Classifies timeout, reset, HTTP 425/503, malformed response, executor crash, and other transport/protocol failures.
- `src/main/java/com/vokerg/voktrader/executor/ExecutorOrderResponse.java`
  - Distinguishes an explicit terminal `REJECTED` response from non-terminal `accepted=false` responses.
- `src/main/java/com/vokerg/voktrader/trade/outbox/OrderDispatchOutboxRepository.java`
  - Adds unresolved-state lookup and evidence-backed state transitions.
- `src/main/java/com/vokerg/voktrader/trade/outbox/OrderDispatchStateService.java`
  - Persists ambiguous outcomes, lists unresolved exposure, and applies accepted/rejected/manual-review resolutions.
- `src/main/java/com/vokerg/voktrader/trade/outbox/OrderDispatchWorker.java`
  - Quarantines ambiguous exceptions and non-terminal HTTP-200 responses instead of retrying or falsely rejecting them.
- `src/main/java/com/vokerg/voktrader/trade/outbox/TransactionalOrderIntentService.java`
  - Refuses new acceptance while any unresolved order-dispatch exposure exists.
- `src/main/java/com/vokerg/voktrader/api/runtime/OrderDispatchUncertaintyController.java`
  - Exposes unresolved evidence and explicit operator resolution actions.
- `src/test/java/com/vokerg/voktrader/trade/outbox/UnknownSubmissionOutcomeTest.java`
  - Verifies required ambiguity classes, no blind retry, exposure blocking, operator evidence, manual review, and non-terminal HTTP-200 quarantine.

## Design Decisions

### Typed failure classification
Executor-boundary failures are classified before they reach durable order state. The required timeout, connection reset, HTTP 425, HTTP 503, malformed response, and executor-crash classes all retain a stable reason code and diagnostic evidence.

### Quarantine before retry
An ambiguous dispatch is persisted as `UNKNOWN`. An expired submission lease is persisted as `RECONCILE`. Non-unique remote truth is escalated to `MANUAL_REVIEW`. None of these states are claimable by the normal order-dispatch worker, so no blind submission retry can occur.

### Conservative exposure blocking
`TransactionalOrderIntentService.accept(...)` checks for `UNKNOWN`, `RECONCILE`, or `MANUAL_REVIEW` rows before persisting a new accepted intent. This intentionally favors capital safety over availability until remote truth is resolved.

### Evidence-backed operator resolution
Operators can list unresolved submissions and resolve them only with explicit evidence. Accepted resolution requires a remote order ID; rejected resolution requires evidence that no order exists; non-unique evidence escalates to manual review. The controller is covered by the existing live control-plane authentication rule for `/api/runtime/**`.

### Only explicit rejection is terminal
An executor response is deterministically rejected only when `accepted=false` and `status=REJECTED`. Responses such as `FAILED`, `ERROR`, blank status, or another unknown status are non-terminal and become durable `UNKNOWN`.

### Scope remained narrow
T023 did not remove the remaining immediate executor calls owned by T024, did not redesign cancellation owned by T025, did not change strategy parameters, and did not enable live capital. T042 and PR #12 were not touched.

## Tests Run
- GitHub Actions CI run #291 (`31071199732`) on implementation head `0fe35bbc33f2fd0e512aa412863112c63711dcac`: **PASS**.
- Java failure-baseline gate:
  - Tests represented in Surefire XML: 339.
  - Temporarily allowed baseline identities: 24.
  - Unexpected or changed identities: 0.
  - Resolved baseline identities: 0.
- `UnknownSubmissionOutcomeTest`: 4 tests passed.
  - Required ambiguity classes persist `UNKNOWN` and never blind-retry.
  - HTTP-200 `accepted=false,status=FAILED` persists `UNKNOWN` rather than false rejection.
  - Unresolved outcome blocks conflicting acceptance until evidence-backed resolution.
  - Non-unique remote truth escalates to `MANUAL_REVIEW` and remains exposure-blocking.
- Python executor tests: **PASS**.
- Clean PostgreSQL migration: **PASS**.
- Angular tests and production build: **PASS**.
- Static repository checks: **PASS**.
- Secret scan: **PASS**.
- Java/executor compose smoke: **PASS**.

## Defects Found During Implementation or Review
- Self-review found that the sidecar durable idempotency ledger classifies an exception-derived HTTP-200 `status=FAILED` result as `UNKNOWN`, while the JVM worker previously treated every `accepted=false` response as a terminal rejection.
- Fixed by requiring explicit `status=REJECTED` for deterministic rejection and quarantining all other non-terminal responses.
- Added a regression test proving the JVM and sidecar now preserve the same ambiguous-outcome authority.

## Safety Impact
- Ambiguous remote submissions can no longer be silently converted into deterministic failures.
- No normal worker retry occurs after timeout, reset, 425, 503, malformed response, executor crash, expired lease, or non-terminal executor response.
- New exposure remains blocked while remote order truth is unresolved.
- Manual review is explicit when remote truth is not unique.
- No live-capital setting or strategy threshold changed.

## Backward Compatibility
- Existing accepted and explicit rejected executor responses retain their previous terminal behavior.
- The existing order-outbox schema already contained unknown-outcome fields, so no new migration was required.
- Runtime status endpoints are additive.
- Existing known Java failure identities remain unchanged.

## Remaining Risks
- T024 still owns removal of remaining executor calls from transactional or immediate-submit paths.
- T023 provides evidence-driven reconciliation transitions but does not add automatic exchange lookup by `clientOrderId`; operators must supply verified remote evidence.
- The global unresolved-exposure block is deliberately conservative and may pause unrelated order acceptance.
- T025 still owns the durable cancellation lifecycle.
- T026 remains the mandatory integrated phase-exit gate after T024 and T025.

## Follow-up Tasks
- T024 is now READY according to the ledger. It was not claimed or started in this PR.
- No new task was created by T023.

## Completion Checklist
- [x] Acceptance criteria met.
- [x] Focused tests added and passing.
- [x] Full CI no-regression policy passing.
- [x] Required report added.
- [x] Phase ledger updated.
- [x] Task index updated.
- [x] Self-review completed and discovered blocker fixed.
- [x] T042 and PR #12 untouched.
