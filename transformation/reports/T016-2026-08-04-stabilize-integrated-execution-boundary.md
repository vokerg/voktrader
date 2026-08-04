# Implementation Report - T016

## Summary

Validated the integrated central-entry-risk boundary after T012 merged and classified every remaining Java failure identity under a durable order-lifecycle owner. This checkpoint changes no production code: the merged T012 implementation already satisfies the architecture, exactly-once risk, persistence-order, and exit/cancel requirements.

The integrated suite is below the checkpoint baseline: 314 tests produce 6 failures and 18 errors, compared with the original 301-test baseline of 7 failures and 18 errors. The exact machine-readable allowance contains 24 identities, with no unexpected or changed identity.

## Task

- Task ID: T016
- Task section: `transformation/tasks/CHECKPOINT-2026-08-03.md#t016---stabilize-integrated-execution-boundary-after-central-risk-merge`
- Branch: `task/T016-integrated-execution-checkpoint`
- PR: #27
- Status at completion: DONE

## Files changed

- Added this checkpoint report and exact failure-ownership map.
- Marked T016 DONE in the checkpoint ledger.
- Made T013, T015, and T020 READY in their detailed phase ledgers.
- Updated the task index so T013 is the next sequential task.
- No production source, test source, migration, workflow, or failure-baseline file changed.

## Design decisions

1. Treat T016 as an evidence and ownership gate, not another implementation task. T012 already restored the T011 typed strategy boundary and enforced central entry approval across compatibility and primary PAPER/LIVE order routing.
2. Assign each remaining failure to exactly one primary T020-T025 owner. A failure may be exercised by later integration gates, but the primary task is the earliest contract that must make its behavior correct.
3. Record zero direct baseline identities for T022 rather than forcing an artificial assignment. Durable idempotency remains mandatory because T023 and T026 depend on it, even though the current suite lacks a restart/idempotency failure identity.
4. Keep T042 paused in its existing PR #12. Its dependency is satisfied by T016, but its stale claim must be reconciled separately and is not absorbed into this checkpoint.

## Tests run

### Integrated full CI evidence

GitHub Actions run #214, run ID `30937723242`, executed on the final T012 head that was squash-merged as `8fcc47b60539ee0251a5c56e18c40f4ea4ef24f6`:

```text
Angular tests and build: success
Clean PostgreSQL migration: success
Python tests: success
Java failure baseline: success
Java and executor compose smoke: success
Static repository checks: success
Secret scan: success
```

Java command enforced by CI:

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
Tests represented in Surefire XML: 314
Minimum expected tests: 311
Raw Maven result: 6 failures, 18 errors, 2 skipped
Current failure identities: 24
Temporarily allowed identities: 24
Unexpected or changed identities: 0
Resolved baseline identities: 0
```

Focused integrated boundary evidence:

```text
StrategyExecutionBoundaryArchitectureTest: passed
CentralEntryRiskArchitectureTest: 5 passed
StrategyIntentBoundaryTest: 4 passed
ExecutionRouterRiskBoundaryTest: 2 passed
RoutingOrderGatewayTest: 4 passed
LiveOrderGatewayRiskBoundaryTest: 3 passed
RiskCheckServiceTest: 5 passed
```

The T016 PR also runs the same seven-job matrix on the documentation-only checkpoint changes before merge.

## Remaining failure ownership

### T020 — transactional outbox acceptance and rejection persistence (2)

| Failure identity | Why T020 owns it |
| --- | --- |
| `OrderLifecycleIntegrationTest.fakEntryZeroFillRejectedLeavesNoPosition` | Rejected accepted work must persist a durable intent/order/outbox result without creating exposure. |
| `OrderLifecycleIntegrationTest.fokEntryRejectedNoFill` | FOK rejection persistence belongs to the acceptance and outbox schema contract. |

### T021 — worker, claim lease, and persisted order/position transitions (9)

| Failure identity | Why T021 owns it |
| --- | --- |
| `StrategyV2OrderLifecycleIntegrationTest.partialDonePositionDoesNotExitWhenPartialExitDisabled` | Strategy-visible partial state depends on worker-applied durable lifecycle transitions. |
| `StrategyV2OrderLifecycleIntegrationTest.partialDonePositionRoutesExitThroughStrategyV2Engine` | The worker must materialize the partial position consumed by Strategy V2 exit logic. |
| `StrategyV2OrderLifecycleIntegrationTest.partiallyClosedPositionDoesNotCreateDuplicateBuy` | Correct persisted active state must suppress a new entry after a partial close. |
| `StrategyV2OrderLifecycleIntegrationTest.partiallyClosedPositionRoutesRemainingExitThroughStrategyV2Engine` | Remaining position state must be persisted and visible after worker-applied fills. |
| `StrategyV2OrderLifecycleIntegrationTest.partiallyOpenWithActiveEntryOrderDoesNotExitUntilRemainderDoneUnlessConfigured` | Active entry remainder and partial position state are worker transition responsibilities. |
| `StrategyV2RuntimeStateAwarenessTest.partiallyOpenExposesPartialStateWithoutNormalEntry` | Runtime state must expose worker-persisted partial exposure instead of presenting a fresh-entry state. |
| `OrderLifecycleIntegrationTest.fakEntryPartialFillIsPartialDoneImmediately` | FAK partial completion requires the worker to persist filled and terminal-remainder state atomically. |
| `OrderLifecycleIntegrationTest.fokEntryFullFillThenFakExitFullFill` | Entry and exit fill responses must be applied by the post-commit lifecycle worker. |
| `OrderLifecycleIntegrationTest.gtcEntryPartialFillStillLive` | A partial GTC fill must remain live with correct persisted filled and remaining quantities. |

### T022 — durable executor idempotency (0 direct identities)

No current failure identity directly exercises sidecar/JVM restart idempotency. T022 remains mandatory before T023 and T026 because the current suite does not prove that retrying one durable `clientOrderId` cannot create a second remote order.

### T023 — unknown outcome and reconciliation (2)

| Failure identity | Why T023 owns it |
| --- | --- |
| `OrderLifecycleIntegrationTest.gtcEntryRestsThenFullFillViaReconciliation` | A resting entry becoming filled through remote truth is a reconciliation transition. |
| `OrderLifecycleIntegrationTest.restingExitLaterPartialFillViaReconciliation` | A resting exit partial fill must converge from remote truth without blind resubmission. |

### T024 — remove remote submission from acceptance transactions (2)

| Failure identity | Why T024 owns it |
| --- | --- |
| `OrderManagerTest.filledOrderDelegatesImmediateFillApplication` | Direct `OrderManager` submission/fill coupling must be replaced by post-commit worker application. |
| `OrderManagerTest.orderManagerSellDoesNotCreateNewTradeAndRemainsAvailableWhileUnarmed` | The legacy transactional manager path must be removed or reduced without losing risk-reducing SELL behavior. |

### T025 — durable exit and cancellation lifecycle (9)

| Failure identity | Why T025 owns it |
| --- | --- |
| `OrderLifecycleIntegrationTest.exitFromPartiallyOpenFullClose` | Closing a partial position is an exit lifecycle transition. |
| `OrderLifecycleIntegrationTest.exitFromPartiallyOpenPartialClose` | Partial close quantity and remaining exposure belong to exit lifecycle semantics. |
| `OrderLifecycleIntegrationTest.exitRejectionPreservesPosition` | A rejected exit must leave the existing position intact. |
| `OrderLifecycleIntegrationTest.gtcExitFromPartiallyOpenRestsAsExitPending` | A resting GTC exit must persist pending-exit state without erasing exposure. |
| `OrderLifecycleIntegrationTest.gtdPartialFillThenCancelledRemainder` | Fill-during-cancel and cancelled remainder are cancellation lifecycle behavior. |
| `OrderLifecycleIntegrationTest.gtdZeroFillExpiry` | GTD expiry must become a durable terminal cancellation outcome. |
| `OrderLifecycleIntegrationTest.overSellClampsToHeldShares` | Exit validation must prevent increasing exposure while permitting a full risk-reducing close. |
| `OrderLifecycleIntegrationTest.restingExitRemainderCancelledAfterPartialFill` | A partially filled exit and cancelled remainder must converge correctly. |
| `OrderLifecycleIntegrationTest.secondExitFromPartiallyClosedClosesRemaining` | Subsequent exit quantity must target only remaining settled/provisional exposure. |

Ownership count: T020 2 + T021 9 + T022 0 + T023 2 + T024 2 + T025 9 = 24 exact identities.

## Safety impact

The checkpoint confirms that every new-position path crosses central risk exactly once and persists correlated checks before routing. Raw BUY calls fail closed across compatibility and primary PAPER/LIVE order routes. SELL and cancellation remain available through typed risk-reducing boundaries. No live capability, strategy threshold, order sizing, or executor behavior changes in this PR.

## Backward compatibility

No runtime interfaces or persisted schemas change in T016. The task graph changes only expose work whose prerequisites are now proven.

## Remaining risks

- The 24 owned lifecycle failures remain intentionally allowed until their assigned T020-T025 tasks resolve them and ratchet the baseline.
- T022 has no direct current failure identity, so its restart/idempotency acceptance tests must be added rather than inferred from the existing suite.
- T055 remains PARTIAL until an administrator applies and verifies required branch checks.
- T042 remains a stale paused claim in PR #12 and requires current-head reconciliation before implementation resumes.

## Follow-up tasks

- T013 becomes READY and is the next task under the lowest-ID selection rule.
- T015 and T020 become READY but wait behind T013 unless the index explicitly authorizes parallel work.
- T014 remains BLOCKED on T013.
- T021-T025 remain blocked by their declared lifecycle dependencies.
- No new task is required; all 24 failures map to existing T020-T025 contracts.

## Completion checklist

- [x] Acceptance criteria met
- [x] Tests added/updated — no test change required; existing integrated contracts provide the evidence
- [x] Tests run and results recorded
- [x] Task section status updated
- [x] Task index updated
- [x] No unrelated strategy tuning included
