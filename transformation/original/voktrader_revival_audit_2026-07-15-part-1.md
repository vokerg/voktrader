# Voktrader Revival Audit — Source-Preserved Text, Part 1

> Original PDF: `voktrader_revival_audit_2026-07-15.pdf`  
> SHA-256: `e8039c3a78c4b5bc95e2528cd8dfc36fb43788bcef96e7a3a4f2a0a591bc49bd`  
> Repository snapshot: `main @ 541337c`  
> Audit date: 15 July 2026  
> This Markdown preserves the report's reviewable text and organization. The original PDF remains the authoritative visual artifact.

# VOKTRADER
## Deep Technical, Architectural and Strategy Revival Audit

Repository: `vokerg/voktrader`

## Bottom line

The codebase is worth reviving as a research and execution platform. It is not ready for live capital. The most urgent defects are not cosmetic: the active Strategy V2 order path can bypass the documented central risk service, MATCHED exchange responses are treated as final fills, live order submission lacks durable idempotency, and backtests collapse recorded full depth into a one-level synthetic book.

## How to read this report

This is a revival audit, not a conventional style review. It asks four questions:

1. Can the system preserve money and state correctly?
2. Can its simulations support trustworthy strategy conclusions?
3. Is there a defensible source of trading edge?
4. What sequence of work turns the repository into a coherent product again?

The analysis was based on the current main branch, repository history, configuration, representative production and test code, and then-current official Polymarket documentation. Findings were labeled as confirmed code evidence or inference; runtime verification remained part of Phase 0.

# Executive verdict

## Recommendation: REVIVE, but reset the objective

Preserve the repository and its strongest domain work. Stop treating it as a nearly finished profitable bot. Reframe it as a deterministic market-research and execution platform whose first product is evidence: trustworthy data, reproducible simulations, correct order state, and a fair-value model. Live trading remains disabled until the P0 gates pass.

## What is genuinely good

- **Domain decomposition.** Market discovery, market data, strategy evaluation, execution, risk, order lifecycle, reconciliation, telemetry, persistence, backtesting, and an operator dashboard already exist.
- **Key isolation.** Signing and exchange SDK code live in a Python sidecar rather than the JVM process.
- **State maturity.** Recent work explicitly addresses partial fills, cancellation, reconciliation backoff, and circuit breaking.
- **Research ergonomics.** Strategy V2 provides YAML-defined candidate scoring, conditions, order actions, diagnostics, and per-bot bundles.
- **Data asset.** The application records price snapshots, book summaries, and individual depth levels.
- **Operator visibility.** The Angular dashboard exposes bots, trades, orders, markets, and events.

## What blocks live capital

| Priority | Area | Finding | Gate |
| --- | --- | --- | --- |
| P0 | Risk enforcement | Strategy V2 plus the order layer can route through OrderManager without RiskCheckService. | No live orders until a central gate is unavoidable. |
| P0 | Fill finality | MATCHED is normalized as filled although the lifecycle continues to MINED/CONFIRMED or FAILED. | Introduce provisional fills and settlement reconciliation. |
| P0 | Submission integrity | A remote side effect occurs inside a DB transaction; sidecar idempotency is in-memory. | Durable outbox, persistent client-order registry, and startup recovery. |
| P0 | Backtest validity | Replay ignores recorded depth levels and creates one bid/ask level with aggregate size. | Replay exact recorded levels and event order. |
| P1 | Protocol drift | No market WS PING, no user channel, no dynamic tick-size handling, and stale global fees. | Implement current protocol metadata and channels. |
| P1 | Control-plane security | H2 console, Swagger/Admin/API surface without evident authentication. | Bind locally and authenticate every control action. |
| P1 | Delivery confidence | No workflow runs or commit checks were recorded on the latest commit. | Required CI and reproducible smoke environment. |

## Scorecard

- Architecture foundation: **7/10** — good boundaries and domain vocabulary; duplicate execution paths reduce coherence.
- Execution correctness: **3/10** — lifecycle work exists, but finality, idempotency, and risk-path consistency are unsafe.
- Backtest credibility: **2/10** — valuable time-machine infrastructure, but depth reconstruction invalidates microstructure features.
- Strategy research quality: **3/10** — many hypotheses and filters, little evidence of independent fair value or disciplined out-of-sample work.
- Operational observability: **6/10** — good logs and dashboard objects, missing user WS and settlement truth.
- Security and release hygiene: **3/10** — safe defaults exist, but live arming, control-plane exposure, and absent CI are material.
- Revival potential: **8/10** — enough solid infrastructure and domain work exists to justify repair rather than rewrite.

# 1. What the repository became

Voktrader began as a short-horizon Polymarket bot and evolved into a small trading platform: Spring Boot orchestration and persistence, an Angular console, a Python execution adapter, paper/live/backtest profiles, multi-bot market runtimes, Strategy V2 configuration, order and fill ledgers, and reconciliation workers.

## Technology shape

- Core runtime: Java 22, Spring Boot, WebFlux/WebSocket, JPA, Flyway, H2/PostgreSQL, Admin, and springdoc.
- Execution sidecar: Python 3.11+, FastAPI/Pydantic, and Polymarket CLOB SDK integration.
- Operator console: Angular, RxJS, TypeScript, and routes for dashboard, bots, trades, orders, markets, and events.
- Research model: stored price/depth snapshots, TimeMachine replay, Strategy V2 YAML overrides, and diagnostics.
- Execution model: a legacy execution router beside a detached order gateway/lifecycle manager, with paper/live gateways, reconciliation, and partial-fill states.

## Strongest assets to preserve

- Deterministic BTC/ETH/SOL 5m/15m market-family runtime.
- Order domain with trades, orders, fills, phases, statuses, reconciliation sources, partial fills, and live-backed safety checks.
- Configurable strategy engine separating feature resolution, candidate selection, conditions, and order construction.
- Persisted individual depth levels.
- Telemetry-first instincts visible in logs, dashboards, diagnostic pulses, and event records.

# 2. Where the project derailed

The project did not derail because it became too ambitious. It derailed because ambition accumulated in layers without one invariant connecting research, simulation, risk, and live execution.

## The five turns

1. **Bot to platform — good.** Reusable market discovery, persistence, strategies, and a separate executor.
2. **Strategy proliferation — mixed.** Multiple hard-coded and YAML strategies multiplied hypotheses before a canonical fair-value target existed.
3. **Optimizer before simulator truth — bad.** Parameter tuning became attractive while replay did not match recorded/live depth.
4. **Parallel execution architectures — bad.** The detached order layer was added beside LiveExecutionService without centralizing safety and semantics.
5. **Incident-driven completion — necessary but late.** Cancellation, reconciliation, partial fills, and circuit breakers arrived after exchange state semantics had already fragmented.

## Core strategic mistake

The project optimized the decision surface before defining fair value.

Most active features describe Polymarket itself: midpoint differences, recent midpoint and bid moves, top-of-book microprice, depth imbalance, spread, and book-walk slippage. These are useful execution and regime variables. They are not, by themselves, an estimate of whether an Up token is worth 0.61 or 0.58.

The missing object is:

`P(underlying settlement price > price-to-beat | current underlying, horizon, volatility, basis, and oracle mechanics)`

Because venue price is used as both signal and target, a rising implied probability can be called momentum, resolution pressure, mid edge, or alignment without establishing positive expected value after fees.

## Organizational smells

- Low-information commit messages made rationale difficult to recover and reliable bisection harder.
- No durable issue backlog captured design decisions, incidents, or deferred risks.
- No recorded CI evidence protected the latest commit.
- Refactors and feature work were interleaved across strategies, persistence, execution, risk, and UI.

# 3. Critical engineering and operational findings

## 3.1 Strategy V2 live orders can bypass the documented risk service

**Severity:** P0 — BLOCK LIVE  
**Confidence:** Confirmed  
**Decision:** The live route is not protected by one unavoidable risk boundary.

The live profile enables the order layer. StrategyV2OrderActionBuilder can send intents directly to OrderGateway; RoutingOrderGateway selects LiveOrderGateway; LiveOrderGateway calls OrderManager; and OrderManager submits to the Python executor without a RiskCheckService dependency. By contrast, the legacy ExecutionRouter to LiveExecutionService path calls RiskCheckService before BUY submission.

Kill switch, live-enabled flag, strategy allowlist, maximum order, spread, price freshness, expiry, duplicate-position, and open-live-trade limits can be skipped on the order-layer route.

**Required change:** Create one risk-enforcing entry gateway, or move assessment into the unavoidable acceptance boundary. Make bypass impossible by type/API design. Keep exit risk permissive so safety logic cannot trap a live position.

**Acceptance:** Enumerate every live entry route and prove kill-switch=true prevents the Python client from receiving a command. Removing one check must fail CI. Startup status exposes the effective gate chain.

## 3.2 MATCHED is treated as a final fill even though it is nonterminal

**Severity:** P0 — BLOCK LIVE  
**Decision:** The local ledger can open a position that later fails settlement.

The Python normalizer marks MATCHED as filled. Java immediately persists a fill and opens the trade. The exchange lifecycle continues through MINED, CONFIRMED, RETRYING, or FAILED.

**Required change:** MATCHED creates a provisional fill/position; only CONFIRMED becomes settled. Subscribe to the authenticated user WebSocket, preserve RETRYING and FAILED, and reverse provisional inventory and fees deterministically.

**Acceptance:** MATCHED -> RETRYING -> FAILED leaves no settled position; MATCHED -> MINED -> CONFIRMED promotes exactly once; restart between events produces the same ledger.

## 3.3 Exchange side effects occur inside a DB transaction with non-durable idempotency

**Severity:** P0 — BLOCK LIVE

OrderManager.submitOrder is transactional and performs blocking HTTP submission before commit. The sidecar idempotency store is an in-memory TTL cache.

If the exchange accepts an order and the transaction rolls back, a live order can be orphaned. A sidecar restart after acceptance but before response delivery can cause resubmission.

**Required change:** Use a transactional outbox. Persist accepted intent and immutable clientOrderId, commit, then let a worker claim and submit. Persist idempotency and reconcile unknown outcomes before retry. Never hold a DB transaction across the network call.

## 3.4 Backtest replay discards the recorded full order book

**Severity:** P0 — INVALIDATES CURRENT MICROSTRUCTURE RESULTS

Individual depth levels are stored, but BacktestReplayService creates a single best bid with total bid depth and a single best ask with total ask depth. This changes slippage, near-depth bands, microprice, queue/touch behavior, and worst-price calculations.

**Required change:** Replay exact level rows in captured order. Add snapshot-completeness flags and never silently substitute aggregate depth for top-level size.

## 3.5 Fee handling is stale and internally inconsistent

The repository hard-codes fee values. The main fee calculator uses the nonlinear formula, while a Strategy V2 fallback omits `(1-price)`.

**Required change:** Persist per-market fee metadata and use one FeeModel everywhere. Version it in every run manifest.

## 3.6 WebSocket protocol maintenance and metadata drift are incomplete

The market WebSocket subscribes and receives but does not send the required heartbeat. Tick-size and last-trade events are ignored.

**Required change:** Add heartbeat supervision, connection generations, gap metrics, REST reseed after reconnect, dynamic tick handling, and per-market fee metadata.

## 3.7 Order truth is polling-first; authenticated user events and dead-man controls are missing

The sidecar polls order/open-order/fill endpoints but has no user-channel subscriber. Polling adds delay and ambiguity; fallback profile matching can attach the wrong fill to overlapping identical orders.

**Required change:** Consume user-channel events as primary truth, REST as reconciliation, use an exchange-side dead-man schedule, and remove ambiguous fill association.

## 3.8 `single_market_single_position` is not enforced globally across inner strategies

Strategy V2 state is retrieved by bot and inner strategy. One active inner strategy can return control to the loop, allowing another inner strategy to enter the same market.

**Required change:** Add portfolio-level state keyed by bot and market, separate from strategy attribution, and enforce before evaluation and again at central risk.

## 3.9 The live profile is an arming profile, not merely a capability profile

Selecting the live profile sets LIVE mode, disables the kill switch, enables live execution, enables the executor, and disables dry-run.

**Required change:** Separate capability from arming. Require a short-lived arm token, bot allowlist, identity verification, non-default token, and preflight. Arming expires automatically.

## 3.10 Control-plane exposure is too permissive

The dependency/configuration surface includes H2 console, Swagger, Admin, and APIs without an evident authentication dependency.

**Required change:** Bind locally by default, disable consoles in live, add authenticated roles and CSRF protection, and audit mutations.

## 3.11 All bot ticks share a sequential scheduler and can block on synchronous I/O

One slow strategy or order call can delay every bot. Use per-bot bounded actors/executors, enqueue submission through the outbox, and measure decision lag.

## 3.12 Persistence mixes operational state, research output, and schema authorities

File H2, Hibernate schema update, Flyway, and backtest writes coexist. Use Flyway as production authority, operational PostgreSQL, and a separate analytical store/schema.

## 3.13 Risk labels do not always match implemented semantics

`MAX_TRADES_PER_MARKET` counts active trades, not cumulative trades or attempts, so closed trades free quota.

**Required change:** Rename to max-active-trades-per-market or implement active, cumulative, and attempt caps separately.

# 4. Research validity and strategy opportunity

## What the active strategies are betting on

- SNIPER_FOK_A: strong market-implied lead, short momentum, book pressure, and low execution cost.
- ANTI_CHOP_FOK_A: momentum exceeds recent range while the opposite side weakens.
- MDI_FOK_A: microprice and depth imbalance lead visible midpoint movement.
- FLIP_FOK_A: one side leaves the 50/50 zone while the other weakens.
- RP_FOK_B: late-window market-implied lead, momentum, and pressure.
- MK_GTC_B: passive bid when the book appears stable enough for spread/fee edge; disabled.

These are legitimate microstructure hypotheses, but active entries are generally taker FOK. They buy after the market moved, pay spread and fee, and sell when the same endogenous signals weaken.

## `mid_edge` is not statistical edge

Candidate midpoint minus opposite midpoint is approximately `2p - 1` in a sane binary book. At p=0.60 it reports 0.20; this does not mean the contract is twenty cents cheap. Correct trading edge is `q - executable_price - costs`, where q is an independently estimated probability.

## Fair-value object to add

For an Up contract settling on `S(T) > K`, estimate:

`q = P[S(T) > K | current multi-venue spot, K, remaining time, short-horizon volatility, basis, oracle mechanics, market regime]`

Trade only when q minus book-walk price, taker fee, and uncertainty buffer is positive.

## Recommended strategy hierarchy

1. Exogenous fair-value taker.
2. Cross-venue latency/basis.
3. Fair-value-informed maker.
4. Binary parity/paired execution.
5. Endogenous momentum as a timing/regime overlay.
6. Avoid LLMs in the hot path; use them for research tooling and postmortems.

## Why 15-minute markets should be the first revival target

Five-minute contracts compress the problem into latency, settlement mechanics, and microstructure. The report recommends proving the repaired platform first on 15-minute markets before returning to the more competitive and manipulation-sensitive five-minute environment.

## Minimum fair-value model

Start with a transparent baseline. Let `x = ln(S/K)`, tau be time to settlement, and sigma a robust realized-volatility estimate. A zero-drift lognormal baseline can begin with a normal-CDF probability and later add jumps, basis, oracle lag, microstructure, and settlement rules.

Record multi-venue underlying prices/trades, price-to-beat, exact horizon, settlement source, timestamps, book levels, and user order/fill events. Produce q_raw, q_calibrated, uncertainty, model version, feature hash, and executable EV. Diagnose with Brier score, log loss, reliability curves, realized frequency by q bin, edge-decile PnL, slippage, adverse selection, and latency decomposition.

## Research threats to control

- Look-ahead and timestamp error.
- Execution optimism.
- Multiple testing.
- Regime leakage.
- Survivorship and missing ticks.
- Fee and tick drift.
- Incorrect settlement truth.
