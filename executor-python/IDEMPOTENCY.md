# Durable executor idempotency

The executor sidecar uses a local SQLite database as the durable authority for
order submission identities. The default path is:

```text
data/executor-idempotency.sqlite3
```

Set `EXECUTOR_IDEMPOTENCY_DB_PATH` to place the database on a persistent volume.
The configured path must remain stable across process and container restarts.
`EXECUTOR_IDEMPOTENCY_PENDING_WAIT_SECONDS` controls how long a concurrent
duplicate waits for the reservation owner to persist its result; the default is
30 seconds.

## Schema

The `executor_idempotency` table is keyed by the JVM-provided
`client_order_id`/`idempotencyKey` and stores:

| Column | Purpose |
| --- | --- |
| `client_order_id` | Durable unique submission identity and primary key. |
| `status` | `PENDING`, `SUCCEEDED`, `REJECTED`, or `UNKNOWN`. |
| `owner_token` | Ephemeral token held only while one request owns `PENDING`. |
| `response_json` | Exact normalized `OrderResponse` for terminal results. |
| `created_at_ns` | Local creation timestamp in nanoseconds. |
| `updated_at_ns` | Last durable state-transition timestamp in nanoseconds. |

SQLite runs in WAL mode with `synchronous=FULL`, a busy timeout, a primary-key
uniqueness constraint, and state-shape checks. A new key is inserted as
`PENDING` inside `BEGIN IMMEDIATE` before the exchange call is allowed to start.
Only the reservation owner can replace that row with a terminal result.

## State semantics

- `PENDING`: one caller owns the right to submit. Concurrent duplicates wait
  for its durable response and never receive a second submission grant.
- `SUCCEEDED`: the executor returned an accepted response. Repeats return the
  exact stored response.
- `REJECTED`: the executor returned an explicit rejection. Repeats return the
  exact stored rejection.
- `UNKNOWN`: the process restarted with an orphaned `PENDING` row, ownership was
  lost, or the executor returned a non-terminal failure. Repeats fail closed so
  the JVM outbox can reconcile remote truth instead of blindly resubmitting.

On startup, every orphaned `PENDING` row is changed to `UNKNOWN`. This is
deliberately conservative: a process can crash after the exchange accepts an
order but before the response is persisted.

## Retention

Submission identities are retained indefinitely. They are not evicted by TTL
or cache capacity because forgetting an accepted identifier would re-enable a
duplicate remote order. The former `ttl_seconds` and `max_entries` constructor
arguments remain source-compatible but are ignored.

Any future cleanup must be an explicit operational process that proves the
`client_order_id` namespace can never be retried and archives the row before
deletion. Routine cache pruning is unsafe.
