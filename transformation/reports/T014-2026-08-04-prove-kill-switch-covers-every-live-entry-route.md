# Implementation Report - T014

## Summary

Built an executable, mutation-sensitive route matrix proving that LIVE mode with `liveEnabled=true` and `killSwitchEnabled=true` cannot reach `PythonExecutorClient.submit` from any current new-position path. The matrix includes the typed central boundary, both configured execution branches, Strategy V2, legacy strategies, and every lower-level production adapter that can submit remotely.

The route inventory exposed and fixed one real integration defect: production `LegacyStrategyIntentAdapter` still sent entries directly to the raw compatibility router. Legacy entries now use `EntryAcceptanceService`, so they receive the same central risk assessment as Strategy V2 rather than being permanently rejected by a lower-level fail-closed guard.

SELL and cancellation remain available while the kill switch is enabled.

## Task

- Task ID: T014
- Task section: `transformation/tasks/PHASE-1-stop-the-bleeding.md#t014`
- Branch: `task/T014-kill-switch-route-proof`
- PR: #29
- Status at completion: DONE

## Files changed

- Updated `LegacyStrategyIntentAdapter` so production legacy entries use `EntryAcceptanceService` and exits use `ExitSubmissionService`.
- Added `LiveKillSwitchRouteMatrixTest`, an executable matrix with downstream executor tripwires and a mutation-equivalent bypass control.
- Added `LiveEntryRouteInventoryArchitectureTest` to freeze the remote-submit caller inventory and detect future API/manual or outbox submission surfaces.
- Updated this report, the phase ledger, and the task index.

## Design decisions

1. **Exercise both approved and raw routes.** The central boundary is tested with the compatibility branch and order-layer branch. Raw lower-level adapters are also invoked directly to prove defense in depth if a future caller reaches them incorrectly.
2. **Tripwire the remote side effect.** Every downstream test double calls the mocked `PythonExecutorClient.submit` if reached. A route passes only when it is rejected and no executor call is observed.
3. **Keep a negative control.** The mutation-equivalent control deliberately calls the executor and asserts that the matrix helper fails. This proves the harness detects the bypass it is intended to prevent.
4. **Repair, rather than hide, the legacy path.** Production legacy entries now use the typed entry boundary. A deprecated raw-router constructor remains only as a source-compatible test seam; Spring production wiring selects the typed constructor.
5. **Freeze route discovery.** Architecture tests assert that the only current production remote-submit callers are `LiveExecutionService` and `OrderManager`. They also assert that the API layer has no manual BUY surface.
6. **Make future outbox work extend the proof.** There is no outbox submission worker before T020/T021. The inventory test intentionally fails when an outbox/submission worker appears, forcing its route into this matrix rather than allowing silent safety drift.

## Route matrix

| Route | Expected result with LIVE kill switch | Executor submit |
| --- | --- | --- |
| Central typed boundary, compatibility branch | Rejected by central risk | Never |
| Central typed boundary, order-layer branch | Rejected by central risk | Never |
| Legacy strategy adapter | Rejected by central risk | Never |
| Strategy V2 order action builder | Rejected by central risk | Never |
| Raw compatibility `ExecutionRouter` | Rejected for missing approved decision | Never |
| Raw primary `RoutingOrderGateway` | Rejected for missing approved decision | Never |
| Raw `LiveOrderGateway` | Rejected for missing approved decision | Never |
| Direct `LiveExecutionService` | Rejected by compatibility risk assertion | Never |
| Direct `OrderManager` | Rejected by live capability/arm gate | Never |
| API/manual entry route | No production surface exists; architecture-enforced | N/A |
| Outbox submit worker | Not yet implemented; architecture guard requires future matrix extension | N/A |

Risk-reducing matrix:

| Route | Expected result with LIVE kill switch |
| --- | --- |
| Typed SELL/exit boundary | Available and routed |
| Existing-order cancellation | Available and delegated |

## Tests run

### Full CI

GitHub Actions run #246, run ID `30941553247`:

```text
Angular tests and build: success
Clean PostgreSQL migration: success
Python tests: success
Java failure baseline: success
Java and executor compose smoke: success
Static repository checks: success
Secret scan: success
```

### Java suite and baseline evidence

Command executed by CI:

```bash
./mvnw -B -ntp test
python scripts/ci/check_java_failure_baseline.py \
  --reports target/surefire-reports \
  --baseline .github/ci/java-failure-baseline.json \
  --ledger transformation/tasks/CHECKPOINT-2026-08-03.md \
  --maven-log java-test.log \
  --maven-exit-code "$maven_status" \
  --summary java-failure-summary.md
```

Result:

```text
Tests represented in Surefire XML: 324
Minimum expected tests: 311
Raw Maven result: 6 failures, 18 errors, 2 skipped
Current failure identities: 24
Temporarily allowed identities: 24
Unexpected or changed identities: 0
Resolved baseline identities: 0
Java evidence artifact: 8905189553
```

Focused T014 coverage inside the suite:

```text
LiveKillSwitchRouteMatrixTest: 3 passed
LiveEntryRouteInventoryArchitectureTest: 4 passed
```

The first run, CI #245 (`30941239530`), failed during test compilation because the new matrix imported `PolymarketFeeCalculator` from an obsolete package. The incorrect import was removed. The corrected run passed without changing production safety behavior or expanding the 24-identity baseline.

## Safety impact

Every currently reachable LIVE new-position path is now covered by executable no-submit proof. The legacy strategy route has been brought back behind central risk rather than relying on an accidental permanent rejection. The matrix will fail if any tested route reaches remote submission, and the architecture inventory will fail when a new production submitter, API/manual entry surface, or outbox worker appears without corresponding coverage. SELL and cancellation remain outside new-exposure gating.

## Backward compatibility

Legacy strategies keep their existing `routeEntry` and `routeExit` calls. Production wiring changes from raw router access to the typed boundaries. A deprecated compatibility constructor preserves existing narrow tests without affecting Spring wiring.

## Remaining risks

- T020/T021 will introduce a durable outbox submission route. Those tasks must extend the matrix before their worker can pass architecture tests.
- Direct remote calls remain in `LiveExecutionService` and `OrderManager` until T024 removes them from transactional acceptance paths; both are currently covered by the matrix.
- The 24 pre-existing lifecycle failures remain unchanged and assigned to T020-T025.
- This proof covers the current repository route inventory; architecture tests are the control that prevents an unreviewed new route from silently escaping it.

## Follow-up tasks

- T015 remains READY and becomes the next task under the lowest-ID rule.
- T020 remains READY behind T015.
- T020/T021 must extend the route matrix when the outbox submit worker is introduced.
- No new task or task reordering is required.

## Completion checklist

- [x] Acceptance criteria met
- [x] Tests added/updated
- [x] Tests run and results recorded
- [x] Task section status updated
- [x] Task index updated
- [x] No unrelated strategy tuning included
