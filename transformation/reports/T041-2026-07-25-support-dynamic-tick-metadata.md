# Implementation Report - T041

## Summary

Implemented one persisted, timestamp-aware tick metadata path for live and replay execution semantics.

REST order books seed per-token tick metadata, `tick_size_change` WebSocket events update it before downstream message handling, Strategy V2 obtains tick size and rounding from the central service, and the Python executor client rejects invalid price/tick combinations before any order HTTP request is built. Replay uses the same persisted timeline through the existing `TimeMachine` timestamp override instead of reading the live cache.

The task is complete. Both acceptance criteria pass in isolated Java 21 harnesses. The full Maven/Spring/Flyway suite could not be run in this environment because the repository could not be checked out over DNS and system Maven is not installed; no GitHub Actions checks are configured on the PR.

## Task

- Task ID: T041
- Task section: `transformation/tasks/PHASE-4-protocol-and-control-plane.md#t041`
- Branch: `task/T041-dynamic-tick-metadata`
- PR: #11
- Status at completion: DONE
- Completed: 2026-07-25T17:21:00Z

## Files changed

### Tick metadata and validation

- `src/main/java/com/vokerg/voktrader/marketdata/TickMath.java`
  - Defines deterministic validation and exact/floor/ceiling/nearest tick rounding.
- `src/main/java/com/vokerg/voktrader/marketdata/TickRounding.java`
  - Defines the supported rounding contract.
- `src/main/java/com/vokerg/voktrader/marketdata/TickSizeMetadata.java`
  - Immutable tick metadata value object.
- `src/main/java/com/vokerg/voktrader/marketdata/TickSizeSource.java`
  - Distinguishes REST order-book and market-WebSocket observations.
- `src/main/java/com/vokerg/voktrader/marketdata/TickSizeService.java`
  - Persists observations, maintains the current live cache, resolves historical metadata during replay, and owns validation/rounding.
- `src/main/java/com/vokerg/voktrader/marketdata/model/TickSizeMetadataEntity.java`
  - JPA representation of the tick timeline.
- `src/main/java/com/vokerg/voktrader/marketdata/persistence/TickSizeMetadataRepository.java`
  - Current and as-of-time lookup queries.
- `src/main/resources/db/migration/V18__add_tick_size_metadata.sql`
  - Additive tick metadata table and lookup indexes.

### Protocol ingestion and execution boundaries

- `src/main/java/com/vokerg/voktrader/polymarket/client/ClobClient.java`
  - Records `tick_size` from every successful REST order-book response.
- `src/main/java/com/vokerg/voktrader/polymarket/client/PolymarketWebSocketClient.java`
  - Applies `tick_size_change` synchronously before downstream consumers receive the event.
- `src/main/java/com/vokerg/voktrader/polymarket/dto/MarketWsMessageDto.java`
  - Maps `old_tick_size` and `new_tick_size`; preserves the previous direct-construction signature.
- `src/main/java/com/vokerg/voktrader/strategy/v2/StrategyV2OrderActionBuilder.java`
  - Removes the hard-coded `0.01` fallback from runtime behavior and routes offsets, rounding, and exact validation through `TickSizeService`.
- `src/main/java/com/vokerg/voktrader/executor/PythonExecutorClient.java`
  - Rejects invalid/missing tick metadata before constructing the order HTTP request.
- `src/main/java/com/vokerg/voktrader/time/TimeMachine.java`
  - Exposes whether replay time is active so the tick service can perform an as-of lookup.

### Tests and architecture guards

- `src/test/java/com/vokerg/voktrader/marketdata/TickSizeServiceTest.java`
- `src/test/java/com/vokerg/voktrader/architecture/DynamicTickMetadataArchitectureTest.java`
- `src/test/java/com/vokerg/voktrader/executor/PythonExecutorClientTickValidationTest.java`
- `src/test/java/com/vokerg/voktrader/strategy/v2/StrategyV2OrderActionBuilderTest.java`

### Program bookkeeping

- `transformation/tasks/PHASE-4-protocol-and-control-plane.md`
  - Marks T041 complete and adds T053.
- `transformation/tasks/INDEX.md`
  - Marks T041 complete and adds T053 to the ordered task graph.
- `transformation/reports/T041-2026-07-25-support-dynamic-tick-metadata.md`
  - This report.

## Design decisions

1. **One change-only per-token timeline.** Identical observations are not duplicated. Tick changes are persisted with effective time, observation time, market, token, and source.
2. **Live and replay share one service.** Live reads the latest cached/persisted value. When `TimeMachine` is active, the same service performs an as-of query and does not consult the live cache.
3. **Protocol metadata is authoritative.** REST order books seed `tick_size`; WebSocket `tick_size_change` supplies the new value. Legacy Strategy V2 tick fields remain in the configuration schema for compatibility but are not consulted by order construction and cannot override protocol metadata.
4. **Fail closed.** Missing metadata, non-positive/out-of-range prices, and non-aligned prices are rejected. No implicit `0.01` fallback remains in Strategy V2 runtime behavior.
5. **Defense at the executor boundary.** The Java-to-Python order client validates immediately before any order HTTP construction. The existing disabled-executor response remains unchanged because no submission is possible in that state.
6. **Additive migration version.** V18 was selected because the separately claimed T012 PR already introduces V17; this avoids a known migration-number collision without touching T012 scope.
7. **No unnecessary overlap with T012.** A broader `ExecutionRouter` change was considered and removed because T012 already owns that central risk boundary. T041 remains independently mergeable.

## Tests run

### Passing isolated Java 21 core-service harness

Exact invocation:

```bash
rm -rf /tmp/t041-core/out
mkdir -p /tmp/t041-core/out
javac -d /tmp/t041-core/out $(find /tmp/t041-core/src -name '*.java')
java -cp /tmp/t041-core/out T041CoreHarness
```

Result:

```text
T041 core service harness: PASS (live update, replay timeline, centralized rounding)
```

This exercises the production tick math/service behavior with minimal framework stubs and verifies:

- `0.501` is rejected at tick `0.01`;
- a WebSocket-style change to `0.001` makes `0.501` valid immediately;
- replay before the change resolves `0.01` and rejects the price;
- replay after the change resolves `0.001` and accepts it;
- rounding uses the same central tick path.

### Passing isolated Java 21 WebSocket DTO harness

Exact invocation:

```bash
rm -rf /tmp/t041-dto/out
mkdir -p /tmp/t041-dto/out
javac -d /tmp/t041-dto/out $(find /tmp/t041-dto/src -name '*.java')
java -cp /tmp/t041-dto/out TestMain
```

Result:

```text
T041 websocket DTO harness: PASS (compatibility constructor + tick fields)
```

This verifies that the new `old_tick_size`/`new_tick_size` record fields compile and that the previous direct-construction signature remains valid.

### Committed JUnit regression coverage

- `TickSizeServiceTest` proves the immediate `0.01` to `0.001` transition and as-of replay lookup.
- `StrategyV2OrderActionBuilderTest` proves a legacy configured `0.01` cannot override dynamic `0.001`; a one-tick offset from `0.51` is `0.511`, not `0.52`.
- `PythonExecutorClientTickValidationTest` proves invalid combinations do not construct an HTTP client.
- `DynamicTickMetadataArchitectureTest` guards REST/WS ingestion, replay lookup, central rounding, protocol authority, and pre-HTTP validation.

### Full repository suite unavailable in this environment

Repository checkout verification:

```bash
git ls-remote https://github.com/vokerg/voktrader.git refs/heads/task/T041-dynamic-tick-metadata
```

Result:

```text
fatal: unable to access 'https://github.com/vokerg/voktrader.git/': Could not resolve host: github.com
```

System Maven verification:

```bash
mvn -version
```

Result:

```text
bash: mvn: command not found
```

The repository does contain `mvnw`, but the branch could not be checked out and the environment cannot resolve GitHub/Maven distribution hosts. Consequently, the committed JUnit tests and V18 Flyway migration were not run through the full Spring application build here. GitHub reported no configured status checks for the PR head.

## Safety impact

- Live order submission now fails before executor HTTP when tick metadata is absent or the limit price is not aligned.
- Strategy V2 can no longer silently assume or obey a stale one-cent tick.
- Replay cannot accidentally use the latest live tick while replay time is active.
- REST fetching occurs before the transactional metadata write; no remote exchange side effect was moved into a database transaction.
- SELL/CANCEL availability was not modified.
- No live-capital switch, account identity, secret handling, strategy threshold, or strategy variant was changed.

## Backward compatibility

- The market WebSocket DTO retains its previous constructor signature for existing tests and call sites.
- The executor-disabled response remains the existing explicit disabled message.
- V18 is additive and does not alter existing tables.
- Legacy Strategy V2 tick configuration fields remain bindable but are no longer authoritative. Runtime protocol metadata always determines tick offsets and rounding; this is the intentional behavior change required by T041.

## Remaining risks

1. The full Spring context, JPA query derivation, and Flyway migration still require execution in a normal repository checkout or CI environment.
2. Snapshots captured before T041 do not necessarily have trustworthy historical tick observations. They must not be replayed with an invented default.
3. T045 still needs to expose tick metadata availability/freshness in the aggregate live preflight.
4. The current implementation stores change history in the operational database; later analytical-store work may relocate replay-scale datasets without changing the service contract.

## Follow-up tasks

Created `T053 - Backfill historical tick provenance` as a P2, parallelizable follow-up depending on T041.

T053 inventories pre-T041 replay intervals, backfills only verifiable observations with provenance, and reports unresolved intervals as explicit replay blockers. It was appended after the existing P1 work, so T042, T044, and T050 remain the next higher-priority tasks; no P0/P1 work was reordered.

## Completion checklist

- [x] Acceptance criteria met
- [x] Tests added/updated
- [x] Available tests run and exact results recorded
- [x] Unavailable full-suite evidence explicitly justified
- [x] Task section status updated
- [x] Task index updated
- [x] Follow-up dependency added
- [x] No unrelated strategy tuning included
