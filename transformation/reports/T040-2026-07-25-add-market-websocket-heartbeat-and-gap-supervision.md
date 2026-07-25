# Implementation Report - T040

## Summary

Implemented market WebSocket heartbeat and gap supervision for the shared market-price feed. The client now sends the current Polymarket text heartbeat (`PING`) every 10 seconds, treats `PONG` and all inbound payloads as liveness evidence, retries normal as well as exceptional disconnects, and reports connection lifecycle events to the feed.

Each shared feed now owns a deterministic supervisor with connection generation IDs and counters for reconnects, gaps, stale heartbeats, pauses, and reseeds. Disconnects, stale heartbeats, and exchange-timestamp gaps fail closed: cached price and depth reads become unavailable, incoming market updates are ignored, and strategy-readable state remains paused until every tracked token has been REST-reseeded successfully.

## Task

- Task ID: T040
- Task section: `transformation/tasks/PHASE-4-protocol-and-control-plane.md#t040`
- Branch: `task/T040-market-websocket-supervision`
- PR: #10
- Status at completion: DONE

## Files changed

- `src/main/java/com/vokerg/voktrader/polymarket/client/PolymarketWebSocketClient.java`
- `src/main/java/com/vokerg/voktrader/polymarket/client/MarketWebSocketObserver.java`
- `src/main/java/com/vokerg/voktrader/marketdata/MarketStreamSupervisor.java`
- `src/main/java/com/vokerg/voktrader/marketdata/MarketPriceFeedService.java`
- `src/main/java/com/vokerg/voktrader/marketdata/LatestPriceState.java`
- `src/main/java/com/vokerg/voktrader/marketdata/OrderBookState.java`
- `src/test/java/com/vokerg/voktrader/marketdata/MarketStreamSupervisorTest.java`
- `src/test/java/com/vokerg/voktrader/marketdata/MarketDataReadGateTest.java`
- `src/test/java/com/vokerg/voktrader/marketdata/MarketPriceFeedSupervisionTest.java`
- `src/test/java/com/vokerg/voktrader/marketdata/MarketWebSocketProtocolArchitectureTest.java`
- `transformation/tasks/PHASE-4-protocol-and-control-plane.md`
- `transformation/tasks/INDEX.md`
- `transformation/reports/T040-2026-07-25-add-market-websocket-heartbeat-and-gap-supervision.md`

## Design decisions

1. **Use the exchange text-heartbeat contract.** The verified Polymarket market-channel contract requires sending the literal text `PING` every 10 seconds and receiving `PONG`. WebSocket control-frame ping behavior was not substituted for that application-level protocol.
2. **Preserve the existing consumer API.** `subscribeToMarketData(List<String>, Consumer<MarketWsMessageDto>)` remains unchanged. Consumers that also implement `MarketWebSocketObserver` receive connection, raw-payload heartbeat, and disconnect callbacks.
3. **Assign generations at the transport boundary.** Every successful socket connection increments a feed-local generation. Generation 1 may use the initial REST seed; every later generation enters `RESEEDING` and remains unreadable until strict reseed completion.
4. **Separate storage from readability.** `LatestPriceState` and `OrderBookState` keep their existing no-argument behavior, while feed-owned instances use a supervisor-supplied read gate. During a gap, read APIs return empty values even if a writer has not yet cleared a structure.
5. **Make reconnect reseed complete before publication.** The strict reseed first obtains an order book for every configured token. Partial results are not published. Only after the complete set is installed does the supervisor return to `HEALTHY`.
6. **Supervise both transport and event continuity.** Raw inbound traffic drives heartbeat freshness. Parsed exchange timestamps additionally detect large forward gaps and regressions. The default exchange-event gap is deliberately conservative at five minutes to avoid treating quiet markets as disconnected.
7. **Expose operator evidence without adding a schema migration.** The service exposes a typed supervision snapshot and emits structured market events containing generation, state, pause reason, heartbeat/event timestamps, and reconnect/gap/stale/pause/reseed counters.
8. **Do not broaden into entry policy or strategy tuning.** No strategy thresholds, order sizes, trading modes, live-arm settings, or execution semantics were changed.

## Tests run

### Deterministic supervisor behavior

```bash
rm -rf out && mkdir out
javac -Xlint:all -Werror -d out \
  src/main/java/com/vokerg/voktrader/marketdata/MarketStreamSupervisor.java
javac -Xlint:all -Werror -cp out -d out /tmp/T040Harness.java
java -cp out T040Harness
```

Result:

```text
T040 supervisor harness: PASS (30-minute heartbeat soak, generation, gap, pause, reseed)
```

The soak advances a deterministic clock for 30 minutes with a heartbeat every 10 seconds and verifies that every watchdog poll remains healthy. The same harness verifies generation rollover, disconnect pause, timestamp gap detection, and resume only after reseed.

### Shared-feed production syntax and type harness

The harness uses the committed production sources plus minimal dependency stubs. Because Lombok is not installed in the runtime, the harness explicitly supplies the constructor and logger that Lombok generates; all task logic remains byte-for-byte sourced from the committed files.

```bash
rm -rf compile-harness/out && mkdir -p compile-harness/out
javac -Xlint:all -Werror -d compile-harness/out \
  $(find compile-harness/src -name '*.java' | sort)
```

Result:

```text
T040 feed compile harness: PASS (warnings-as-errors; Lombok constructor/logger generated explicitly in harness)
```

### WebSocket-client production syntax and type harness

```bash
rm -rf client-compile-harness/out && mkdir -p client-compile-harness/out
javac -Xlint:all -Werror -d client-compile-harness/out \
  $(find client-compile-harness/src -name '*.java' | sort)
```

Result:

```text
T040 websocket client compile harness: PASS (warnings-as-errors; Lombok constructor/logger generated explicitly in harness)
```

### Source contract harness

```bash
python3 - <<'PY'
from pathlib import Path
client = Path('src/main/java/com/vokerg/voktrader/polymarket/client/PolymarketWebSocketClient.java').read_text()
feed = Path('src/main/java/com/vokerg/voktrader/marketdata/MarketPriceFeedService.java').read_text()
price = Path('src/main/java/com/vokerg/voktrader/marketdata/LatestPriceState.java').read_text()
book = Path('src/main/java/com/vokerg/voktrader/marketdata/OrderBookState.java').read_text()
checks = {
    'literal PING': 'session.textMessage("PING")' in client,
    '10s default': 'ping-interval-ms:10000' in client,
    'PONG liveness': '"PONG".equalsIgnoreCase' in client,
    'normal close retries': 'Polymarket WS connection completed' in client,
    'strict reconnect seed': 'seedStateFromRestOrderBooks(true)' in feed,
    'resume only after seed': feed.index('seedStateFromRestOrderBooks(true)') < feed.index('supervisor.onReseedSucceeded'),
    'price read gate': '!readable.getAsBoolean()' in price,
    'book read gate': '!readable.getAsBoolean()' in book,
    'generation telemetry': 'connectionGeneration' in feed,
    'gap telemetry': 'MARKET_WS_GAP_DETECTED' in feed,
}
assert all(checks.values()), checks
print('T040 source contract harness: PASS (' + ', '.join(checks) + ')')
PY
```

Result:

```text
T040 source contract harness: PASS (literal PING, 10s default, PONG liveness, normal close retries, strict reconnect seed, resume only after seed, price read gate, book read gate, generation telemetry, gap telemetry)
```

### Full Maven suite limitation

A full `./mvnw test` run was not possible in this execution environment. Maven is not installed, the working directory is not a complete repository checkout, and outbound DNS is disabled (`git clone` failed with `Could not resolve host: github.com`), so the Maven wrapper distribution and dependencies could not be resolved. The PR therefore includes normal JUnit/Mockito tests for execution in a complete checkout or CI, in addition to the passing self-contained harnesses above.

## Safety impact

- Market-data-dependent strategy evaluation now fails closed during disconnects, stale heartbeats, detected event gaps, and reconnect reseeding.
- Cached prices and depth are cleared and also guarded at read time, preventing an entry evaluation from using a stale pre-reconnect book.
- A reconnect cannot reopen strategy reads with a partial seed; all tracked token books must be available first.
- This task does not modify order cancellation, detached order reconciliation, or executor APIs. Those control paths remain available independently of the market-data read gate.
- Market-data-dependent exits are intentionally paused with the rest of strategy evaluation while the feed is untrustworthy; this matches the task contract to pause strategy evaluation through data gaps.
- No live-capital enablement, strategy tuning, fee assumption, or risk-limit change is included.

## Backward compatibility

- The public `Consumer<MarketWsMessageDto>` subscription signature is unchanged.
- Existing consumers that do not implement `MarketWebSocketObserver` continue to receive parsed market messages normally.
- `LatestPriceState` and `OrderBookState` retain public no-argument constructors with always-readable behavior for existing Spring and test usage.
- No database migration or mandatory configuration is required. Defaults are 10-second PING cadence, 25-second heartbeat timeout, one-second supervision cadence, five-minute exchange-event-gap threshold, and two-second reseed retry cadence.
- Existing shared-feed semantics remain: one market subscription and one state set are reused across subscribed bots.

## Remaining risks

- The 30-minute soak is deterministic rather than an external live connection to Polymarket. A deployment soak should still confirm infrastructure-specific proxy, DNS, and network behavior.
- The full Maven/JUnit suite was not executable in this restricted runtime; the committed JUnit tests need execution in a complete checkout or future T050 CI.
- Supervision counters are process-local and reset on restart. T045 can expose current health in live preflight, while T061 can persist normalized events for longitudinal analysis.
- Initial feed startup preserves the previous tolerant seed behavior. Reconnect reseeding is strict; an incomplete initial seed remains naturally unreadable for missing outcomes but is not treated as a reconnect failure.

## Follow-up tasks

- T041 is unblocked and changed from `BLOCKED` to `READY`; its dynamic tick metadata work can reuse the supervised market-channel lifecycle.
- T045 remains blocked on its other dependencies and should aggregate `MarketStreamSupervisor.Snapshot` into live preflight.
- T061 remains blocked on T030 and can later persist the structured supervision events.
- No new task was required, and no safety-critical task was reordered or removed.

## Completion checklist

- [x] Acceptance criteria met
- [x] Tests added/updated
- [x] Tests run and results recorded
- [x] Task section status updated
- [x] Task index updated
- [x] No unrelated strategy tuning included
