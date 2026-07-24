# Implementation Report - T010

## Summary

The live profile is now capability-only. Activating `live` keeps the kill switch enabled, starts with no arm lease, and cannot submit a live BUY until all capability checks pass and an explicit account-bound arm with a positive expiry is active.

The primary live execution path records the arm as a blocking `LIVE_ARM` risk check. The detached order-layer path performs the same check inside `OrderManager` before creating a trade/order or invoking the Python executor. SELL exits and cancellation remain outside the arm gate.

## Task

- Task ID: T010
- Task section: `transformation/tasks/PHASE-1-stop-the-bleeding.md#t010`
- Branch: `task/T010-live-profile-capability-only`
- PR: #7
- Status at completion: DONE

## Files changed

- `src/main/java/com/vokerg/voktrader/trade/LiveArmService.java`
  - Adds synchronized in-memory arm/disarm state, account matching, automatic expiry, capability blockers, and operator-facing status.
- `src/main/java/com/vokerg/voktrader/trade/LiveCapabilityStartupValidator.java`
  - Evaluates LIVE capability at startup and reports whether the process is capability-ready or blocked while remaining unarmed.
- `src/main/java/com/vokerg/voktrader/trade/TradingProperties.java`
  - Adds `expectedAccountId` and `liveArmTtl` configuration.
- `src/main/java/com/vokerg/voktrader/trade/RiskCheckService.java`
  - Adds the persisted blocking `LIVE_ARM` entry check to the primary live path.
- `src/main/java/com/vokerg/voktrader/trade/OrderManager.java`
  - Rejects detached LIVE BUY submissions before persistence and executor submission when the arm is absent or expired.
- `src/main/java/com/vokerg/voktrader/api/runtime/RuntimeStatusController.java`
  - Exposes capability readiness, arm timestamps/account, expiry, entry permission, and blocker lists.
- `src/main/resources/application-live.properties`
  - Keeps the kill switch on, adds expected-account and arm-TTL configuration, and leaves the default executor token as an explicit blocker.
- Tests updated/added:
  - `RuntimeStatusControllerTest`
  - `LiveArmServiceTest`
  - `LiveProfileConfigurationTest`
  - `OrderManagerTest`
  - `RiskCheckServiceTest`
- Transformation ledger/report files updated for T010.

## Design decisions

1. **Arm state is independent from live capability.** `live-enabled=true` means the process can be configured for live execution; it does not arm entries.
2. **The kill switch remains an independent gate.** An arm cannot override a kill switch, disabled executor, executor dry-run, default/blank executor token, missing expected account metadata, non-LIVE mode, or invalid TTL.
3. **Arm leases are account-bound and expiring.** The arm request must exactly match `voktrader.trading.expected-account-id`; the lease expires automatically and a process restart always returns to unarmed state.
4. **Entry checks sit at concrete submission boundaries.** The primary path persists `LIVE_ARM` through `RiskCheckService`; the detached path checks inside `OrderManager` before persistence/executor submission. This protects direct callers rather than relying only on routers.
5. **Risk-reducing actions do not require an arm.** SELL exits and cancellation bypass the entry arm gate by design.
6. **No unauthenticated arm endpoint was added.** `LiveArmService` provides the internal arm contract, but exposing an operator mutation belongs with the secured control-plane/preflight work already tracked by T044 and T045.
7. **No secret is exposed in runtime status.** Status reports whether the executor token is non-default, never the token value.

## Tests run

### Isolated Java boundary harness

The connector execution environment did not provide a repository checkout or dependency cache, and the repository has no GitHub workflow on the PR head. A Java 21 harness compiled the exact `LiveArmService` source plus minimal boundary models mirroring the new `RiskCheckService` and `OrderManager` checks.

Exact final command:

```text
ROOT=/tmp/voktrader-t010-boundary-harness; rm -rf "$ROOT/out"; mkdir -p "$ROOT/out"; javac -Xlint:all -Werror -d "$ROOT/out" $(find "$ROOT/src" -name '*.java' | sort); java -ea -cp "$ROOT/out" com.vokerg.voktrader.trade.BoundaryHarness
```

Result:

```text
PASS: T010 boundary harness (16 safety assertions)
```

Covered assertions:

- capability-ready process starts unarmed;
- unarmed and expired BUY entries fail both primary and detached boundary checks;
- detached rejection occurs before trade persistence, order persistence, and executor submission;
- matching account can create a bounded arm;
- arm expires at the exact TTL boundary;
- SELL and cancellation remain available while unarmed;
- default executor token and missing expected-account metadata are reported as blockers.

### Repository tests added

The following repository tests were added or extended but could not be executed through Maven in this connector-only environment:

```text
mvn -Dtest=LiveArmServiceTest,LiveProfileConfigurationTest,RiskCheckServiceTest,OrderManagerTest,RuntimeStatusControllerTest test
```

GitHub Actions runs on PR head `310ff8a5c223a14e5dac29cb967c16b176c4907f`: none.

## Safety impact

- Starting the `live` profile alone no longer permits live entries.
- The committed live profile defaults to kill-switch-on and unarmed.
- Every currently identified live BUY submission boundary checks an active, non-expired arm before executor submission.
- The detached path blocks before creating persistence artifacts.
- Capability blockers are explicit and observable without exposing secrets.
- Exit and cancellation behavior remains available for reducing existing exposure.

## Backward compatibility

- PAPER and BACKTEST execution behavior is unchanged.
- LIVE operators must now configure `VOKTRADER_EXPECTED_ACCOUNT_ID`, provide a non-default executor token, disable the kill switch deliberately, and create an arm through a future secured control-plane integration before entries are possible.
- Runtime status JSON gains an additive `liveArm` object.
- Restarting a process intentionally clears the arm lease.

## Remaining risks

- The repository Maven suite was not runnable in the available connector environment; the committed tests require execution in a normal checkout or future CI.
- Arm state is intentionally process-local and non-durable. This fails safe on restart but is not suitable for coordinated multi-instance arming without later design work.
- No public arm mutation is exposed in this task. Secure authentication, authorization, audit, and live preflight remain prerequisites before an operator-facing arm action is added.
- T014 remains responsible for exhaustive route/mutation proof if new live entry routes are introduced or discovered.

## Follow-up tasks

No new task or dependency reordering was required.

Existing tasks already cover the discovered follow-up scope:

- T014 - exhaustive proof that every live entry route is blocked while safety gates are active, while exits/cancels remain available.
- T044 - secure the live control plane.
- T045 - expose a secured live preflight/operator path that can integrate the internal arm contract.
- T050 - add CI so the committed Maven tests run automatically.

## Completion checklist

- [x] Acceptance criteria met
- [x] Tests added/updated
- [x] Tests run and results recorded
- [x] Task section status updated
- [x] Task index updated
- [x] No unrelated strategy tuning included
