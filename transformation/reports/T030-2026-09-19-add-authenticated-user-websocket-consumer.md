# Implementation Report - T030

## Summary

Added an authenticated Polymarket user WebSocket consumer as the primary real-time source of account order/trade lifecycle evidence.

The JVM adapter:
- authenticates the user channel with L2 API credentials supplied through environment-backed Spring properties;
- never logs, persists, or returns credential values;
- subscribes without a market filter so all account lifecycle events are observed;
- sends protocol heartbeats and reconnects with bounded exponential backoff;
- tracks stream health and connection generation;
- deduplicates order/trade lifecycle events by stable remote identity plus lifecycle progression;
- persists each supported event as raw payload plus normalized remote/event/order/trade metadata;
- projects exact remote-order matches onto durable order audit state in the same processing cycle;
- treats MATCHED/MINED/etc. as lifecycle evidence only, leaving provisional/settled inventory semantics to T031;
- blocks live arming/entry while active live orders or unresolved provisional trade evidence exist and the private stream is unhealthy;
- exposes safe health/generation and exposure state at `GET /api/runtime/user-websocket`.

Polymarket's current user-channel protocol was checked against the upstream WebSocket documentation: `wss://ws-subscriptions-clob.polymarket.com/ws/user`, L2 `apiKey/secret/passphrase` authentication, optional `markets`, order/trade event families, and client `PING` heartbeats.

## Task

- Task ID: T030
- Task section: `transformation/tasks/PHASE-3-exchange-truth.md#t030`
- Branch: `task/T030-user-websocket`
- PR: #38
- Status at completion: DONE

## Files changed

Production:
- `src/main/java/com/vokerg/voktrader/polymarket/user/UserWebSocketProperties.java`
- `src/main/java/com/vokerg/voktrader/polymarket/user/UserWebSocketMessage.java`
- `src/main/java/com/vokerg/voktrader/polymarket/user/UserWebSocketEventEntity.java`
- `src/main/java/com/vokerg/voktrader/polymarket/user/UserWebSocketEventRepository.java`
- `src/main/java/com/vokerg/voktrader/polymarket/user/UserWebSocketEventService.java`
- `src/main/java/com/vokerg/voktrader/polymarket/user/UserWebSocketHealthService.java`
- `src/main/java/com/vokerg/voktrader/polymarket/user/UserWebSocketSafetyService.java`
- `src/main/java/com/vokerg/voktrader/polymarket/user/PolymarketUserWebSocketClient.java`
- `src/main/java/com/vokerg/voktrader/api/runtime/UserWebSocketStatusController.java`
- `src/main/java/com/vokerg/voktrader/trade/LiveArmService.java`
- `src/main/java/com/vokerg/voktrader/trade/model/TradeOrderEntity.java`
- `src/main/resources/application.properties`
- `src/main/resources/application-live.properties`
- `src/main/resources/db/migration/V28__authenticated_user_websocket_events.sql`

Tests:
- `src/test/java/com/vokerg/voktrader/polymarket/user/UserWebSocketHealthServiceTest.java`
- `src/test/java/com/vokerg/voktrader/polymarket/user/UserWebSocketEventServiceTest.java`
- `src/test/java/com/vokerg/voktrader/polymarket/user/UserWebSocketSafetyServiceTest.java`
- `src/test/java/com/vokerg/voktrader/trade/LiveArmUserWebSocketSafetyTest.java`
- `src/test/java/com/vokerg/voktrader/api/runtime/UserWebSocketStatusControllerTest.java`

Transformation records:
- `transformation/tasks/PHASE-3-exchange-truth.md`
- `transformation/tasks/PHASE-5-simulation-honesty.md`
- `transformation/tasks/INDEX.md`
- this report

## Design decisions

1. **Use the Java adapter rather than moving the user channel into the Python sidecar.**
   The JVM owns durable order state, arming, and reconciliation. Consuming account events there allows one-cycle persistence/projection without adding another sidecar-to-JVM event transport.

2. **Use L2 API credentials only.**
   The WebSocket subscription uses `apiKey`, `secret`, and `passphrase`. No private key, funder, or signing key is needed by this consumer. Configuration exposes only a boolean `credentialsConfigured` status.

3. **Subscribe account-wide.**
   The upstream user-channel `markets` field is optional. Omitting it avoids losing lifecycle events for live orders whose market was not included in a transient local subscription set.

4. **Persist before projection.**
   Supported `order` and `trade` messages are written to `user_websocket_events` with raw payload, normalized identifiers/status, receive/event timestamps, and connection generation before local-order projection.

5. **Exact remote-order matching only.**
   T030 never associates lifecycle evidence through token/side/price/size heuristics. Order events use the remote order ID; trade events inspect exact taker/maker remote order IDs. Broader ambiguous fill association remains T033.

6. **Do not collapse trade lifecycle evidence into settlement.**
   MATCHED/MINED/CONFIRMED/RETRYING/FAILED are persisted and projected into order audit fields, but T030 does not update settled inventory or realized PnL. T031 owns that state machine.

7. **Fail closed only when exposure needs the private stream.**
   A disconnected/unhealthy user channel becomes a live capability blocker when there is an active LIVE/POLYMARKET order or unresolved MATCHED/MINED/RETRYING trade evidence. This also makes already-armed entry eligibility turn false after a drop.

8. **Keep REST reconciliation intact.**
   Existing `OrderReconciliationService` remains the repair/audit path. The new socket updates durable lifecycle evidence directly and does not route WebSocket events through REST polling.

## Tests run

GitHub Actions CI run #375 on implementation commit `14f5841e5ff2abb8b6b743bb8f3941d228af407c` completed successfully across every job.

```bash
./mvnw -B -ntp test
```

Result:

```text
Tests run: 367, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS
```

The T030-specific Java tests in that run were green:
- `LiveArmUserWebSocketSafetyTest`: 2/2
- `UserWebSocketSafetyServiceTest`: 2/2
- `UserWebSocketHealthServiceTest`: 2/2
- `UserWebSocketEventServiceTest`: 3/3
- `UserWebSocketStatusControllerTest`: 1/1

```bash
./mvnw -B -ntp -Dtest=CleanDatabaseMigrationTest test
```

Result:

```text
Clean PostgreSQL migration job: success
V28 authenticated-user-WebSocket schema applied on a clean PostgreSQL 16 database.
```

```bash
python -m pytest -q executor-python/tests
npm test -- --watch=false
npm run build
python scripts/ci/check_repository.py
python scripts/ci/check_dependency_locks.py
python -m unittest discover -s scripts/ci/tests -p 'test_*.py' -v
python -m compileall -q executor-python/voktrader_executor scripts/ci
bash scripts/ci/smoke-compose.sh
```

Result:

```text
Python tests: success
Angular tests and build: success
Static repository checks: success
Java and executor compose smoke: success
Secret scan (gitleaks): success
```

Local checkout/test execution was unavailable in this tool environment because outbound DNS to GitHub was blocked, so GitHub Actions is the authoritative compile/test evidence.

No live authenticated WebSocket handshake was performed because doing so would require operator-supplied exchange credentials. No live capital was enabled or submitted.

## Safety impact

- Live BUY acceptance continues to cross the existing central risk and durable-order boundaries.
- A private-stream drop now blocks live arming/entry when active exchange exposure or unresolved provisional trade evidence exists.
- Exit/cancel paths are not made dependent on the arm; T030 does not reduce exit/cancel permissiveness.
- Duplicate user events are ignored deterministically by a unique durable dedupe key.
- Credentials are environment-backed and are never stored in the event ledger, returned by the status endpoint, or included in application log messages.
- Exact remote IDs are required for projection; T030 does not introduce heuristic fill matching.
- Settlement truth is deliberately not changed by MATCHED/MINED alone; T031 remains responsible for provisional versus settled inventory.

## Backward compatibility

- The user WebSocket is disabled by default in the base profile.
- The live profile enables the capability, but missing L2 credentials do not expose values and do not initiate a connection.
- Existing `LiveArmService(TradingProperties, ExecutorProperties)` construction remains available for focused unit tests while Spring uses the safety-aware constructor.
- Existing REST reconciliation and executor APIs are unchanged.
- V28 is additive: one event table plus nullable audit columns on `trade_orders`.

## Remaining risks

- A real Polymarket authenticated handshake/reconnect soak was not run because no exchange credentials were supplied. CI validates protocol construction, event processing, persistence schema, and safety behavior without secrets.
- T030 records trade lifecycle evidence but intentionally does not implement provisional/settled inventory transitions; T031 is now the next required P0.
- T030 uses exact remote IDs and records multi-order exact matches, but the broader ambiguity/manual-review contract is intentionally left to T033.
- Dead-man exchange heartbeat protection remains T035.

## Follow-up tasks

No new tasks were created.

Dependency transitions caused by T030 completion:
- T031 -> READY
- T035 -> READY
- T061 -> READY

No task ordering was otherwise changed.

## Completion checklist

- [x] Acceptance criteria met
- [x] Tests added/updated
- [x] Tests run and results recorded
- [x] Task section status updated
- [x] Task index updated
- [x] No unrelated strategy tuning included
