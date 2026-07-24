# Implementation Report - T011

## Summary

Introduced typed strategy execution boundaries for entries and exits. Strategy V2 now emits `EntryIntent` and `ExitIntent` through separate service interfaces and no longer selects the compatibility router or order gateway. Legacy strategies also emit typed intent but remain behind an explicit compatibility adapter so their execution semantics do not change in this task.

## Task

- Task ID: T011
- Task section: `transformation/tasks/PHASE-1-stop-the-bleeding.md#t011`
- Branch: `task/T011-typed-entry-exit-intents`
- PR: #8
- Status at completion: DONE

## Files changed

- Added `EntryIntent`, `ExitIntent`, `EntryAcceptanceService`, and `ExitSubmissionService`.
- Added `StrategyIntentBoundary` as the only Strategy V2 component that chooses between the compatibility execution router and the order gateway.
- Added `LegacyStrategyIntentAdapter` and migrated shared legacy entry/exit support plus the two deprecated direct-routing strategies to it.
- Migrated `StrategyV2OrderActionBuilder` to construct and submit typed intent only.
- Added route tests and a source architecture test that forbids strategy dependencies on `TradeIntent`, `ExecutionRouter`, `OrderGateway`, and `PythonExecutorClient`.
- Updated the task ledger and index; T012 is now `READY`.

## Design decisions

1. `EntryIntent` can only wrap a BUY and `ExitIntent` can only wrap a SELL. Their generic `TradeIntent` transport is package-private, preventing strategy packages from recovering the lower-level routing object.
2. Entry acceptance and exit submission use separate interfaces. This makes accidental entry/exit substitution visible to the compiler and gives T012 a single entry-policy insertion point.
3. `StrategyIntentBoundary` owns the Strategy V2 order-layer feature flag, configured execution mode, `OrderGatewayContext` override, and compatibility-router fallback.
4. Legacy behavior remains isolated in `LegacyStrategyIntentAdapter`; this task does not silently move legacy strategies onto the Strategy V2 order-layer feature flag.
5. Compatibility constructors accept the historical router object and adapt it internally, preserving existing unit-test construction without retaining router fields or imports in strategy code.

## Tests run

The repository could not be cloned in the execution environment because outbound DNS resolution is unavailable. A temporary branch-only GitHub Actions workflow was also attempted, but GitHub returned no workflow run for the validation commits, so it was removed from the final diff.

Typed boundary contract compilation and routing assertions:

```bash
find /tmp/t011-contract/src -name '*.java' -print0 | xargs -0 javac -d /tmp/t011-contract/out
java -cp /tmp/t011-contract/out com.vokerg.voktrader.trade.ContractHarness
```

Result:

```text
T011 contract compilation and routing assertions passed
```

Strategy V2 builder contract compilation and assertions:

```bash
find /tmp/t011-builder/src -name '*.java' -print0 | xargs -0 javac -d /tmp/t011-builder/out
java -cp /tmp/t011-builder/out com.vokerg.voktrader.strategy.v2.BuilderHarness
```

Result:

```text
StrategyV2OrderActionBuilder contract compilation and assertions passed
```

The harnesses compile the production boundary and builder contracts against repository-compatible signatures and assert:

- entry intent remains BUY-only and exit intent remains SELL-only;
- compatibility mode routes through `ExecutionRouter`;
- order-layer mode routes through `OrderGateway`;
- legacy adapter preserves typed BUY/SELL routing;
- Strategy V2 fixed-share sizing produces the expected notional;
- GTD entry intent carries the configured resting TTL.

Repository tests added for execution in a normal checkout:

```bash
./mvnw -B -ntp -Dtest=StrategyV2OrderActionBuilderTest,StrategyIntentBoundaryTest,StrategyExecutionBoundaryArchitectureTest test
./mvnw -B -ntp -DskipTests test
```

These Maven commands were not executable in this environment because the full checkout was unavailable. This limitation is retained as an explicit review risk rather than represented as a passing Maven run.

## Safety impact

- Removes strategy-level selection of live gateway/executor plumbing.
- Makes new-position and exposure-reducing intent distinct at the type boundary.
- Provides the sole Strategy V2 entry boundary required for centralized risk policy in T012.
- Does not change live arming, kill-switch, order sizing defaults, or trading strategy rules.

## Backward compatibility

- Strategy V2 preserves the existing `use-order-layer` behavior; the feature-flag decision moved into `StrategyIntentBoundary`.
- Legacy strategies retain compatibility-router execution through `LegacyStrategyIntentAdapter`.
- Historical constructor call shapes used by tests remain accepted through compatibility overloads.

## Remaining risks

- The full Maven test tree still needs to run in a normal repository checkout or CI environment.
- Legacy strategies remain on compatibility execution semantics until their planned retirement/migration.
- Central entry risk is not added here; T012 is now eligible and should attach policy to `EntryAcceptanceService`/`StrategyIntentBoundary`.

## Follow-up tasks

No new prerequisite was discovered and no dependency reorder was required. T012 was unblocked and advanced to `READY`. T050 remains the existing parallel task for repository CI, which would close the environment-level validation gap encountered here.

## Completion checklist

- [x] Acceptance criteria met
- [x] Tests added/updated
- [x] Tests run and results recorded
- [x] Task section status updated
- [x] Task index updated
- [x] No unrelated strategy tuning included
