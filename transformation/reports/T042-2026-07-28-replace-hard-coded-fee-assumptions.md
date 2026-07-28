# Implementation Report - T042

## Summary

Claimed T042 and implemented the central deterministic fee-model foundation: immutable per-market fee metadata, rate/exponent/taker-only semantics, a versioned shared calculation contract, persisted metadata timeline storage, and official-schedule/cross-mode parity tests.

The task remains `IN_PROGRESS`. The model and persistence layer are implemented and the isolated core harness passes, but repository-wide wiring into every existing live, paper, replay, run-manifest, and Strategy V2 fee call site could not be completed or verified in this session because the repository code-search index was unavailable and the execution environment could not resolve GitHub for a checkout. The draft PR remains the claim lock rather than overstating acceptance-criteria completion.

## Task

- Task ID: T042
- Task section: `transformation/tasks/PHASE-4-protocol-and-control-plane.md#t042`
- Branch: `task/T042-unified-fee-model`
- PR: #12
- Status at completion: IN_PROGRESS

## Files changed

- `src/main/java/com/vokerg/voktrader/fee/FeeLiquidityRole.java`
  - Defines maker/taker calculation role.
- `src/main/java/com/vokerg/voktrader/fee/FeeMetadata.java`
  - Immutable validated market fee schedule value.
- `src/main/java/com/vokerg/voktrader/fee/FeeModel.java`
  - Shared calculation and model-version contract.
- `src/main/java/com/vokerg/voktrader/fee/UnifiedFeeModel.java`
  - Deterministic rate/exponent fee calculation with taker-only handling and five-decimal protocol precision.
- `src/main/java/com/vokerg/voktrader/fee/FeeMetadataEntity.java`
  - JPA persistence representation.
- `src/main/java/com/vokerg/voktrader/fee/FeeMetadataRepository.java`
  - Latest and as-of metadata timeline queries.
- `src/main/java/com/vokerg/voktrader/fee/FeeService.java`
  - Central persistence, resolution, calculation, and version access service.
- `src/main/resources/db/migration/V19__add_fee_metadata.sql`
  - Additive fee metadata table and lookup index.
- `src/test/java/com/vokerg/voktrader/fee/UnifiedFeeModelTest.java`
  - Official schedule example, deterministic mode parity, maker-zero, and input validation tests.
- `transformation/tasks/PHASE-4-protocol-and-control-plane.md`
  - Records the claim and PR metadata.
- `transformation/tasks/INDEX.md`
  - Marks T042 in progress and advances the unclaimed queue.
- `transformation/reports/T042-2026-07-28-replace-hard-coded-fee-assumptions.md`
  - This report.

## Design decisions

1. **Metadata, not constants, drives fees.** The calculation accepts market-provided `rate`, `exponent`, and `takerOnly` values and has no default fee schedule.
2. **One deterministic contract.** Mode is deliberately not an input. Live, paper, replay, and Strategy V2 must pass identical economic inputs to the same `FeeModel` and therefore cannot diverge by mode-specific formulas.
3. **Taker-only behavior is explicit.** Maker fee is exactly zero when metadata declares the schedule taker-only.
4. **Historical lookup is supported.** The repository exposes latest and as-of queries so replay can resolve the schedule effective at replay time instead of consulting a current constant.
5. **Model version is a first-class value.** `polymarket-fee-v1` is exposed by the model/service for run-manifest wiring. The existing manifest call sites still need to be identified and updated before T042 can be marked done.
6. **Fail closed on missing metadata.** `FeeService` throws for an absent latest/as-of schedule rather than inventing a fallback.
7. **Protocol precision.** Fee output is rounded to five decimal places, matching the documented minimum fee precision.

## Tests run

### Passing isolated Java 21 model harness

Exact invocation:

```bash
rm -rf /tmp/t042 && mkdir -p /tmp/t042/src/com/vokerg/voktrader/fee /tmp/t042/src/org/springframework/stereotype /tmp/t042/out
# production fee value types/model plus a minimal @Component stub and T042Harness were written under /tmp/t042/src
javac -d /tmp/t042/out $(find /tmp/t042/src -name '*.java')
java -cp /tmp/t042/out T042Harness
```

Result:

```text
T042 fee model harness: PASS (official schedule, parity, taker-only)
```

The harness verifies:

- rate `0.02`, exponent `2`, 100 shares, and price `0.50` produce `0.12500`;
- four mode labels using identical inputs produce byte-for-byte equal `BigDecimal` results;
- taker-only metadata produces a zero maker fee.

### Full repository suite unavailable

Checkout attempt:

```bash
git clone --branch transformation/2.0 --single-branch https://github.com/vokerg/voktrader.git /tmp/voktrader
```

Result:

```text
fatal: unable to access 'https://github.com/vokerg/voktrader.git/': Could not resolve host: github.com
```

The GitHub connector also reported no workflow run for the branch head, and repository code search returned no indexed results. Consequently, the Spring/JPA/Flyway suite and repository-wide fee-call-site audit remain outstanding.

## Safety impact

- Missing fee metadata fails closed instead of silently underestimating execution cost.
- Replay can use an as-of metadata path rather than current-state assumptions.
- No trading mode, arming control, account identity, secret, strategy threshold, order submission, exit, or cancellation path was changed.
- No remote exchange side effect was added inside a database transaction.
- Because integration is incomplete, this PR does not yet claim that every live entry or replay path consumes the central service.

## Backward compatibility

- All additions are additive; no existing public Java contract was removed.
- Migration V19 creates a new table only.
- No fallback is introduced for markets without recorded fee metadata.

## Remaining risks

1. Identify and replace every existing hard-coded fee formula and stale fallback in live, paper, replay, reporting, and Strategy V2 paths.
2. Wire `FeeService.modelVersion()` into the existing run-manifest representation.
3. Ingest fee details from the current CLOB market-info/market-event protocol into `FeeService.record`.
4. Run the complete Maven test suite and clean-database Flyway migration test.
5. Confirm V19 does not collide with commits merged into `transformation/2.0` after this branch was created.

## Follow-up tasks

No new task was created or reordered. The outstanding work is inside T042's original acceptance criteria, so it remains `IN_PROGRESS` on draft PR #12 rather than being split or silently deferred.

## Completion checklist

- [ ] Acceptance criteria met
- [x] Tests added/updated
- [x] Available tests run and results recorded
- [x] Task section status updated
- [x] Task index updated
- [x] No unrelated strategy tuning included
