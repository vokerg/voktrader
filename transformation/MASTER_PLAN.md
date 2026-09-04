# Voktrader 2.0 Master Plan

## Executive goal

Bring Voktrader back as a small but serious trading research and execution platform. The goal is not to make the current strategy knobs prettier. The goal is to restore architectural trust: one path from data to decision to risk to order to settlement to ledger to replay.

Voktrader 2.0 succeeds when live, paper, shadow, and replay all share the same contracts for market data, strategy intent, portfolio risk, fee/tick metadata, order lifecycle, settlement truth, and experiment evidence.

## Non-goals during the transformation

- Do not add new alpha strategies before replay and order truth are fixed.
- Do not tune YAML thresholds against the current replay results.
- Do not enable broad live trading.
- Do not treat MATCHED as a final fill.
- Do not preserve duplicate execution paths for nostalgia.
- Do not add dashboards unless they support safety, evidence, or mismatch review.

## Target architecture

```text
market data + underlying feeds
        |
        v
normalized event log ---------------> deterministic replay
        |                                  |
        v                                  v
feature + fair-value model ----------> strategy intent only
        |                                  |
        +------------------+---------------+
                           v
                 central entry risk gate
                           |
                           v
                  durable order outbox
                           |
                           v
                  executor adapter/sidecar
                           |
                           v
          user WS + REST reconciliation + dead-man
                           |
                           v
         provisional/confirmed ledger and operator evidence
```

## Core invariants

### I1 - Strategy emits intent only

Strategies, including Strategy V2, are decision components. They must not decide execution plumbing. They emit a typed entry or exit intent and receive a result.

### I2 - Entries cross one risk gate

Every new-position BUY must pass the same entry risk service. This covers Strategy V2, legacy strategies while they remain, API/manual routes, paper, shadow, replay, and live.

### I3 - Exits reduce exposure and remain available

Entry risk limits must not trap a live position. Exits and cancellations validate position/order ownership and quantity, but they do not reuse entry exposure gates in a way that blocks risk reduction.

### I4 - Remote side effects are outbox-driven

A database transaction may accept an intent and enqueue work. It may not call the exchange. A worker submits after commit using a durable client order ID and a claim lease.

### I5 - Settlement is explicit

MATCHED is provisional. CONFIRMED is settled. RETRYING, FAILED, CANCELLED, and UNKNOWN are first-class states. PnL and settled inventory must not be derived from provisional truth.

### I6 - Replay is not a separate strategy universe

Replay must use the same feature resolver, fee model, tick model, risk policy, order semantics, and ledger state machine as live. Differences must be explicit simulator adapters.

### I7 - Promotion is evidence-gated

No phase may advance because the bot "looks good". Every phase has exit criteria, tests, and reports.

---

# Phase 0 - Transformation foundation

## Objective

Create the operating system for the revival: branch, plan, task registry, agent workflow, and immutable baseline.

## Deliverables

- `transformation/` directory committed to the transformation branch.
- Original audit preserved under `transformation/original/`.
- `AGENTS.md` updated with task selection, claim, implementation, report, and completion protocol.
- Task index and task ledgers created.
- Initial PR opened from the transformation branch.

## Exit gate

An agent can start from a clean checkout, read `AGENTS.md`, and determine the next eligible task without asking the operator for extra context.

---

# Phase 1 - Stop the bleeding

## Objective

Prevent unintended live exposure and remove architecture paths that bypass safety.

## Workstreams

### 1.1 Live arming separation

The live profile must become capability-only. Starting the app in a live-capable profile should not arm trading. Arming requires explicit short-lived operator action, expected account identity, clean reconciliation, healthy market/user WS, executor identity, non-default token, and declared loss budget.

### 1.2 Central entry risk

Create a single entry boundary. The boundary receives a typed entry intent, materializes market/portfolio/account snapshots, runs risk checks, persists the decision, and only then enqueues accepted work.

### 1.3 Portfolio invariants

Separate strategy ownership from portfolio exposure. A Strategy V2 inner strategy may own a position for attribution, but portfolio caps must be keyed by bot, market, token/outcome, account, and mode as configured.

### 1.4 Kill-switch route tests

Tests enumerate all routes capable of producing a BUY/new-position intent. With kill switch enabled in LIVE mode, no test may observe a Python executor submit call.

### 1.5 Exit/cancel permissiveness

SELL and CANCEL paths must remain available to reduce or neutralize exposure. They require ownership/quantity/order validation, not entry exposure approval.

## Exit gate

- Kill-switch test covers Strategy V2 order layer, Strategy V2 router path, legacy strategy path, and any API/manual entry path.
- Startup status exposes effective live gate chain.
- No production component can call the Python submit method for an entry without an accepted risk decision.

---

# Phase 2 - Durable order lifecycle

## Objective

Make order submission recoverable, idempotent, and auditable.

## Workstreams

### 2.1 Transactional outbox

Intent acceptance, risk result, immutable client order ID, and OUTBOX_READY record are committed together. A worker claims the outbox row after commit.

### 2.2 Durable idempotency

Client order IDs must survive JVM restarts, sidecar restarts, and retries. The sidecar either persists idempotency or treats the Java clientOrderId/exchange client ID as authoritative.

### 2.3 Unknown outcome handling

Timeouts, 425, 503, network disconnects, and ambiguous responses must not blind-retry. Unknown outcomes enter RECONCILE or MANUAL_REVIEW with a bounded retry policy.

### 2.4 Cancellation lifecycle

Cancellation is an evented state transition: cancel requested, remote cancel submitted, cancel acknowledged, cancelled/filled/unknown resolved.

### 2.5 Submission chaos tests

Fault injection should kill the JVM, sidecar, and database at every boundary. After restart, each accepted intent maps to at most one remote order and one local order.

## Exit gate

No network call occurs inside the transaction that accepts the intent. Restart after any simulated failure converges to a known local/remote state without duplicate exchange orders.

---

# Phase 3 - Exchange truth and settlement ledger

## Objective

Represent the exchange lifecycle accurately and stop treating provisional matches as settled inventory.

## Workstreams

### 3.1 User WebSocket consumer

Consume authenticated order and trade lifecycle events. Use user WS as primary truth and REST as reconciliation.

### 3.2 Provisional vs settled ledger

MATCHED creates provisional inventory. MINED advances settlement. CONFIRMED updates settled inventory and realized PnL. FAILED reverses provisional state deterministically.

### 3.3 Fill association

Remove ambiguous profile-based fill matching. Require remote trade/order IDs or a bounded unique candidate; otherwise manual review.

### 3.4 Startup reconciliation

Before arming, reconcile all nonterminal orders, provisional fills, local positions, remote open orders, balances, and account identity.

### 3.5 Dead-man controls

For any resting live order, maintain an exchange-side heartbeat/dead-man schedule so open orders cancel if the process dies.

## Exit gate

Synthetic sequences MATCHED->FAILED and MATCHED->MINED->CONFIRMED produce the correct settled/provisional ledger across restarts. App refuses to arm when user WS is unavailable or mismatches are unresolved.

---

# Phase 4 - Protocol currency and control-plane hardening

## Objective

Keep the bot connected to the current exchange protocol and make operational control safe.

## Workstreams

### 4.1 Market WS heartbeat and gap handling

Send required PING, supervise PONG/connection generation, reseed after reconnect, and pause strategy decisions through gaps.

### 4.2 Dynamic tick and fee metadata

Fetch, persist, and apply per-market tick size and fee metadata. One FeeModel must serve live, paper, replay, and reports.

### 4.3 Matching engine modes

Map 425 and 503 responses, post-only/cancel-only windows, Retry-After, and restricted modes into explicit local states.

### 4.4 Security

Add authenticated roles, live local bind, H2/Swagger/Admin disabling in live, CSRF protection for browser mutations, and immutable audit events for control-plane actions.

### 4.5 Preflight and status

Expose a preflight/status endpoint that tells an operator exactly whether trading is possible and why not.

## Exit gate

Anonymous live control-plane mutations fail. Heartbeat/reconnect soak test passes. Tick/fee changes alter validation immediately. Startup refuses unsafe live-capable configuration.

---

# Phase 5 - Simulation honesty

## Objective

Make backtests evaluate the same market and order reality that live saw.

## Workstreams

### 5.1 Full-depth replay

Load recorded individual depth levels, not a synthetic one-level book. Validate summary rows against level-derived best bid/ask/depth.

### 5.2 Event log

Persist source timestamps, receive timestamps, connection generations, REST reseeds, WS deltas, user events, and underlying references.

### 5.3 Execution models

Implement taker book-walk, FOK/FAK semantics, maker queue assumptions, partial fills, cancellation latency, and settlement model variants.

### 5.4 Live/replay parity

Golden tests prove the same captured event produces the same feature vector, risk decision, fee, tick rounding, and order intent.

### 5.5 Separate stores and manifests

Separate operational state from analytical data and backtest outputs. Every run has an immutable manifest.

## Exit gate

Golden-market replay reconstructs full book features exactly. Old strategy PnL is archived as non-comparable and all strategies are rerun under the new simulator.

---

# Phase 6 - Fair-value layer and strategy simplification

## Objective

Move from endogenous market-price signals to a falsifiable probability estimate.

## Workstreams

### 6.1 Underlying composite

Record at least two underlying venue feeds with bid/ask, trades, source timestamps, receive timestamps, and outlier handling.

### 6.2 Contract reference data

Persist price-to-beat, start/end horizon, settlement source/rule, market family, and oracle mechanics.

### 6.3 Baseline probability model

Implement a transparent baseline q model with calibration by asset/horizon. Start simple and falsifiable before adding sophistication.

### 6.4 Executable EV

Compute q minus book-walk price, fees, latency/slippage reserve, and model uncertainty buffer. Strategies may only request entries when executable EV is positive under configured confidence assumptions.

### 6.5 Strategy simplification

Reduce active Strategy V2 configs to one taker baseline plus disabled maker candidate. Treat current microstructure features as overlays, not standalone alpha.

## Exit gate

On untouched chronological holdouts, q calibration is acceptable and executable-edge bins are monotonic. Net simulated PnL remains positive under pessimistic fee/latency/fill assumptions.

---

# Phase 7 - Robustness, shadow, and paper

## Objective

Prove the system can survive realistic production failures before risking capital.

## Workstreams

### 7.1 Walk-forward discipline

Train/tune on past windows, validate on next block, freeze, and evaluate on an untouched forward block. Report BTC/ETH/SOL and 5m/15m separately.

### 7.2 Shadow execution

Generate live intents without submitting. Compare shadow decisions against subsequent books and user/order events.

### 7.3 Operational chaos

Exercise restarts, DB failure, sidecar failure, stale feed, delayed user WS, partial fill, cancellation race, 425/503, and clock skew.

### 7.4 Paper trial

Run paper through the same order/risk/replay contracts and compare fill predictions to shadow/live-observed outcomes.

### 7.5 Automatic demotion

Any data integrity, reconciliation, calibration, or loss-budget breach turns arming off and requires review.

## Exit gate

Chaos suite passes. Shadow and paper diagnostics stay within declared bands. No unresolved reconciliation mismatch exists.

---

# Phase 8 - Tiny live promotion

## Objective

Promote the smallest possible live scope.

## Scope

- One market family, preferably 15m first.
- One account.
- One strategy.
- Small order budget.
- Hard daily loss budget.
- Manual arm with automatic expiry.
- Automatic demotion on stop conditions.

## Exit gate

Sufficient independent live opportunities demonstrate calibration, fill quality, and drawdown inside confidence bands. Expansion is based on trade count and evidence, not elapsed time.

---

# Global stop conditions

Stop live/promotion if any of these occur:

- unresolved local/remote order mismatch;
- user WebSocket unhealthy while live orders/provisional fills exist;
- source-to-receive or decision-to-submit latency exceeds the strategy budget;
- actual fees or tick metadata differ from manifest;
- calibration or fill performance breaches the declared band;
- daily loss, drawdown, settlement failure, or reconciliation circuit breaker triggers;
- any route can submit a live entry without central risk acceptance.
