# Voktrader Revival Audit — Source-Preserved Text, Part 2

> Original PDF: `voktrader_revival_audit_2026-07-15.pdf`  
> SHA-256: `e8039c3a78c4b5bc95e2528cd8dfc36fb43788bcef96e7a3a4f2a0a591bc49bd`  
> Repository snapshot: `main @ 541337c`  
> Audit date: 15 July 2026  
> This Markdown preserves the report's reviewable text and organization. The original PDF remains the authoritative visual artifact.

# 5. Target architecture

The report recommends a modular-monolith architecture with an isolated executor and one authoritative lifecycle. A rewrite is not justified. Java remains suitable for domain state, orchestration, and APIs; Python remains suitable for exchange SDK isolation and research. The problem is duplicated semantics and non-durable boundaries, not the language choice.

## Required modules and invariants

| Module | Invariant |
| --- | --- |
| market-data | Normalizes market WS, user WS, REST seeds, and underlying feeds. Persists source time, receive time, sequence/hash, and connection generation. |
| fair-value | Produces versioned q estimates and uncertainty; has no execution side effects. |
| strategy | Consumes a point-in-time view and emits intents. It cannot call exchange clients or databases directly. |
| portfolio-risk | Only entry gateway. Owns exposure, duplicate, cumulative-trade, freshness, market, account, and kill-switch policies. |
| order-outbox | Durable state machine from accepted intent through claim, acknowledgement, provisional match, confirmation, cancellation, or failure. |
| executor-adapter | Signs, submits, and cancels; maps protocol errors and restricted modes; stores durable idempotency; exposes identity and capabilities. |
| ledger | Authoritative positions and PnL derived from confirmed events; provisional inventory is separate. |
| replay | Runs the same strategy, risk, fee, tick, and lifecycle contracts over recorded events through a simulated executor. |
| control-plane | Authenticated, audited commands and a manual-review queue; no direct database-repair buttons. |

## State machine that matches exchange reality

- Intent: CREATED -> RISK_ACCEPTED or RISK_REJECTED. No network call before acceptance is committed.
- Order: OUTBOX_READY -> CLAIMED -> SUBMITTING -> ACKNOWLEDGED. ClientOrderId is immutable and durable.
- Resting order: OPEN or PARTIALLY_FILLED -> CANCEL_REQUESTED -> CANCELED. User WS is primary; REST proves convergence.
- Trade fill: MATCHED_PROVISIONAL -> MINED -> CONFIRMED. Only CONFIRMED changes settled inventory.
- Failure: RETRYING, FAILED, or UNKNOWN -> RECONCILE or MANUAL_REVIEW. No blind retry after ambiguous submission.
- Position: NONE -> PROVISIONAL -> OPEN -> PARTIALLY_CLOSED -> CLOSED/RESOLVED. Settled and provisional quantities are separately queryable.

## Observability must answer operator questions

- **Can the bot trade?** Effective profile, arm expiry, kill switch, executor identity, user WS health, market WS health, dead-man schedule, and unresolved orders.
- **Why did it trade?** Model probability, executable price, fees, slippage, uncertainty, rule IDs, feature snapshot, and risk checks.
- **What is true now?** Confirmed positions, provisional matches, open and cancel-pending orders, last reconciliation, and mismatches.
- **Is research comparable?** Code SHA, config hash, data hash, fee/tick model version, simulator version, and train/validation/test ranges.
- **Is the system lagging?** Source-to-receive, receive-to-decision, queue wait, submit RTT, match-to-confirm, and scheduler backlog.

# 6. Revival sequence

## Phase 0 — Stop the bleeding

1. Unify live risk behind the sole live entry gateway.
2. Add provisional MATCHED/MINED/CONFIRMED/RETRYING/FAILED states and user-WS ingestion.
3. Add a durable outbox and persistent idempotency.
4. Reconcile all nonterminal orders and positions before arming.
5. Implement current protocol heartbeat, dynamic tick, per-market fees, restart modes, and dead-man controls.
6. Secure the control plane.
7. Add Java, Python, Angular, migration, static, secret, and smoke CI.

**Phase 0 exit:** Chaos tests can kill any process during submit, match, confirm, or cancel and restart into a converged ledger with no duplicate exchange orders. Kill-switch tests cover every strategy path. No unresolved local/remote mismatch exists at arm time.

## Phase 1 — Make simulation honest

1. Replay exact depth levels.
2. Persist initial books, deltas, reconnect generations, user events, and underlying data.
3. Model taker book-walk, FOK/FAK, maker queues, partial fills, and cancellation latency.
4. Prove live/replay parity for features, risk, fees, and order intent.
5. Report skipped/gapped events, warmup, stale data, and resolution coverage.
6. Separate operational and analytical stores with immutable run manifests.

**Phase 1 exit:** Golden-market replays reproduce recorded book features exactly. Paper/live shadow fills fall inside declared simulator bands. Old PnL is archived as non-comparable and strategies are rerun from zero.

## Phase 2 — Add a real fair-value layer

1. Build an underlying composite from at least two venues with outlier handling and clock discipline.
2. Capture price-to-beat, settlement source/rule, and exact horizon.
3. Implement a transparent baseline probability model calibrated by asset and horizon.
4. Compute executable EV after full book walk, fees, latency/slippage reserve, and uncertainty.
5. Simplify to one taker baseline and one disabled maker candidate; use Strategy V2 microstructure features as overlays.

**Phase 2 exit:** On untouched chronological holdouts, forecasts are calibrated and executable-edge bins are monotonic. Net simulated PnL remains positive under pessimistic assumptions.

## Phase 3 — Prove robustness before capital

- Walk-forward only.
- Report assets and horizons separately.
- Generate live shadow intents without submitting.
- Exercise restart, disconnect, 425/503, stale feed, delayed user WS, partial fill, DB failover, and clock-skew scenarios.
- Automatically demote on any data-integrity, reconciliation, or calibration breach.

## Phase 4 — Tiny live promotion

Promote one 15-minute market family, one strategy, one account, and a hard daily loss budget. Use manual arming with automatic expiry. Do not run all Strategy V2 variants together. Increase scope only after enough independent opportunities exist to estimate fill quality, calibration, and drawdown.

# 7. Prioritized backlog

## P0

- Central entry risk gateway for legacy, V2, paper, replay, and live; exits never blocked by entry limits.
- Provisional/confirmed settlement model and user-WS consumer.
- Durable order intent, claim lease, clientOrderId, and unknown-outcome reconciliation.
- Startup preflight for account, chain, funder, balances, approvals, executor capability, clocks, and remote orders.
- Replay exact depth-level entities.

## P1

- Market WS heartbeat and connection generation.
- User WS events with persistence and dedupe.
- Dead-man schedule for resting live orders.
- Per-market fee and tick metadata.
- Explicit 425/503 restart modes.
- Spring Security, local bind, disabled live consoles, and audited roles.
- Required CI.
- Portfolio invariant across Strategy V2 inner strategies.
- Source/receive time model.
- Unambiguous fill association.
- Locked dependencies and adapter contract tests.

## P2

- Per-bot scheduler isolation and nonblocking outbox.
- Flyway-only schema authority, operational PostgreSQL, and separate analytics.
- Separate active, cumulative, and attempt risk caps.
- Immutable experiment registry.
- Data-quality and gap reporting.
- Underlying venue and settlement metadata.
- Calibration diagnostics.
- Operator review queue and evidence dashboards.

# 8. Experiment discipline

## Every run is an immutable scientific object

A run manifest records:

- Identity: run ID, parent hypothesis, author, creation time, code SHA, and dirty-tree flag.
- Data: dataset hash, market IDs, asset/horizon, coverage, exclusions, and exact ranges.
- Model: feature, probability, calibration, fee, and tick versions.
- Strategy: full YAML, active inner strategy, order rules, risk policy, and portfolio scope.
- Simulator: fill model, queue assumptions, latency distribution, cancellation delay, and settlement model.
- Evaluation: predeclared metric, minimum trades, holdout, stress scenarios, and promotion threshold.
- Result: calibration, net PnL, drawdown, fill/adverse-selection, sensitivity, failure reasons, and artifacts.

## Metrics that matter

- Forecast: Brier score, log loss, reliability slope/intercept, calibration error, and realized frequency by q bin.
- Opportunity: quoted edge, executable edge, uncertainty-adjusted edge, duration, and capacity.
- Execution: submit RTT, fill ratio, full/partial/reject, slippage, adverse selection at 1/3/10/30 seconds, and cancel latency.
- Portfolio: net PnL, return on deployed capital, max drawdown, loss streak, exposure time, and tail loss by regime.
- Integrity: book gaps, WS reconnects, timestamp inversion, local/remote mismatch, and provisional failure rate.

## Promotion gates

- Research -> candidate: falsifiable hypothesis, acceptable full-depth coverage, no holdout access during tuning.
- Candidate -> backtest pass: positive uncertainty-adjusted EV on untouched data and robust stress results.
- Backtest -> shadow: parity tests pass, no safety bypass, complete diagnostics, immutable manifest.
- Shadow -> paper: live feature vectors and decisions match replay; reconciliation/data gaps remain within limits.
- Paper -> tiny live: all P0s closed, chaos suite passes, operator preflight clean, loss budget and arm expiry configured.
- Tiny live -> expand: sufficient independent fills and predicted-versus-realized execution/calibration within confidence bands.

## Stop conditions

- Unresolved local/remote order mismatch.
- User WebSocket unhealthy while live orders or provisional fills exist.
- Clock offset or source-to-receive latency beyond the declared budget.
- Actual fee/tick metadata differs from the manifest.
- Calibration or fill performance breaches the promotion band.
- Daily loss, drawdown, repeated settlement failures, or reconciliation circuit breaker triggers.

# 9. Final recommendation

## Do not throw this repository away

The repository contains enough valuable domain work to justify repair. A rewrite would likely recreate the same mistakes with less tested state vocabulary. The correct move is a controlled architectural reset around four invariants:

1. One central risk gate.
2. Durable exactly-once intent identity.
3. Confirmed exchange settlement.
4. One deterministic full-depth event model shared by live and replay.

## Stop doing immediately

- Tuning thresholds or adding Strategy V2 variants against the current replay.
- Running live through the order-layer route until risk bypass and settlement finality are fixed.
- Treating midpoint lead or momentum as edge without independent q.
- Adding dashboard features unrelated to safety, evidence, or mismatch review.
- Using vague commit messages and large mixed changes as project memory.

## Do next, in order

1. Create a revival branch and tag the current main snapshot as pre-audit baseline.
2. Write failing tests for the four P0 findings before changing production code.
3. Unify risk and order submission behind an outbox; add user WS and provisional settlement.
4. Fix protocol metadata, heartbeat, security, and CI.
5. Rebuild replay from exact depth levels and rerun existing strategies as a fresh generation.
6. Add underlying/price-to-beat data and a transparent fair-probability baseline.
7. Promote one 15-minute taker strategy through walk-forward, shadow, paper, and tiny-live gates.

## Success definition

The revived project succeeds even before earning money if it can answer, reproducibly and in real time:

- What probability did we estimate, and why?
- What price and quantity were executable?
- Which risk checks passed?
- What did the exchange acknowledge, match, mine, confirm, fail, or cancel?
- Can replay reconstruct the same decision?
- Would the result survive pessimistic cost and latency assumptions?

Once those answers are trustworthy, strategy work becomes science instead of configuration roulette.

# Appendix A — Patch sketches

## Central entry gateway

```text
strategy -> EntryIntentService.accept(intent)
         -> portfolioSnapshot + marketSnapshot
         -> riskPolicy.assess(...)
         -> transaction { save checks; save intent; save OUTBOX_READY }
         -> accepted/rejected

outbox worker -> durable claim -> executor.submit(clientOrderId) -> append event
```

No strategy or order manager can reach the executor directly.

## Provisional settlement ledger

Store acknowledgement, match, mining, confirmation, retry, and failure as distinct events. Position queries expose settledShares and provisionalShares.

## Full-depth replay loader

For each capture time, load levels by market, outcome, side, and levelIndex. Build the book from those lists. Assert that summary best bid/ask/depth matches values derived from levels; reject incomplete ticks.

## Live preflight

- Expected chain, signer, funder/proxy, and API account identity match configured fingerprints.
- Non-default bearer token and accepted executor/SDK capability.
- Healthy market and user WebSockets, fresh heartbeat, bounded clock offset.
- No unknown/nonterminal order older than policy; local/remote open-order sets reconcile.
- Fee/tick metadata exists for every active token.
- Balances and allowances are sufficient but capped.
- Kill switch stays on until a short-lived arm action names exact bots and loss budget.

# Appendix B — Questions data must answer before promotion

1. How often does q exceed executable ask by 1, 2, 3, and 5 cents after fees?
2. Does realized win frequency match q by probability bin and horizon?
3. How much apparent edge disappears under 100/250/500/1000ms latency shifts?
4. What is adverse selection after taker buys and maker fills at 1/3/10/30 seconds?
5. How does edge differ by asset, horizon, time-to-expiry, and volatility regime?
6. What fraction of FOK attempts reject, partially fill, or execute worse than estimated?
7. How often do MATCHED trades become RETRYING or FAILED, and how long to CONFIRMED?
8. How often does the book reconnect/reseed, and are strategies paused through gaps?
9. Do multiple Strategy V2 rules identify the same opportunity, and is incremental value real?
10. What capacity exists before book walk and fees erase edge?
11. How sensitive is maker PnL to queue-ahead and cancellation-latency assumptions?
12. Does a frozen 15-minute baseline retain edge forward?

# Appendix C — Repository evidence map

- C01 README.md — project scope and safe default.
- C02 docs/runbook.md — modes, strategy namespaces, and documented risk gates.
- C03 pom.xml — Spring/Java dependencies and absent evident Spring Security starter.
- C04 application.properties — DB, consoles, fees, strategy, and backtest settings.
- C05 application-live.properties — live arming, executor, and reconciliation settings.
- C06 StrategyV2OrderActionBuilder.java — order-layer route bypassing ExecutionRouter.
- C07 RoutingOrderGateway.java — mode-based gateway selection.
- C08 LiveOrderGateway.java — direct delegation to OrderManager.
- C09 OrderManager.java — transactional remote submission, fill persistence, and reconciliation.
- C10 LiveExecutionService.java — legacy path applies RiskCheckService before BUY.
- C11 RiskCheckService.java — risk checks and active-trade count semantics.
- C12 BacktestReplayService.java — replay collapses depth.
- C13 MarketDepthSnapshotLevelEntity.java — persisted individual levels.
- C14 StrategyV2FeatureResolver.java — feature definitions, mid_edge, and fee fallback.
- C15 PolymarketFeeCalculator.java — nonlinear fee formula.
- C16 PolymarketWebSocketClient.java — subscription/retry without periodic PING.
- C17 MarketWsMessageDto.java — handled events and missing tick fields.
- C18 executor-python/pyproject.toml — sidecar dependencies and SDK.
- C19 executor main.py — API, in-memory idempotency integration, response handling.
- C20 executor polymarket_client.py — MATCHED normalization and polling/fill fallback.
- C21 executor config.py — defaults and split limits.
- C22 executor idempotency.py — in-memory TTL warning.
- C23 PythonExecutorClient.java — blocking WebClient integration.
- C24 TradeExecutionSafetyService.java — paper-exit block for live-backed trades.
- C25 dashboard/package.json — Angular/TypeScript toolchain.
- C26 dashboard app.routes.ts — console routes.
- C27 BookOrderFillSimulator.java — maker touch/cross assumptions.
- C28 strategy-v2.deep-research.yml — active inner strategies and assumptions.
- C29 MarketPriceFeedService.java — shared feeds, snapshots, and receipt timestamps.
- C30 MarketDepthSnapshotEntity.java — book summary and aggregate depth.
- C31 BotRuntimeManager.java — sequential bot ticks.
- C32 BotRuntime.java — market selection, rollover, and shared feed.
- C33 MarketFamily.java — BTC/ETH/SOL 5m/15m families.
- C34 Commit history — optimizer, Strategy V2, lifecycle, reconciliation, and partial fills.
- C35 Latest commit checks — no recorded combined status/workflow runs for the audited SHA.

# Appendix D — External-source categories used by the original audit

The original audit consulted official Polymarket documentation for fees, market and user WebSockets, matching-engine restart behavior, developer tooling, and heartbeat/dead-man controls. It also cited then-current reporting and preprints concerning short-duration crypto markets, settlement manipulation, ghost fills, and prediction-market order-book measurement. Exact URLs and publication metadata remain in the original PDF.

# Appendix E — Glossary

- CLOB: central limit order book.
- FOK: fill or kill; full immediate execution or cancellation.
- FAK: fill and kill; immediate available quantity fills and remainder cancels.
- GTC: good till canceled.
- GTD: good till date/expiration.
- q: independent estimated probability that the outcome resolves true.
- Executable edge: q minus actual book-walk price, fees, slippage, and uncertainty reserve.
- Provisional fill: off-chain match not yet terminally confirmed.
- Outbox: durable DB record submitted by a worker after acceptance commits.
- Dead-man switch: exchange-side schedule that cancels open orders if heartbeats stop.
- Parity test: proof that live and replay compute the same state, feature, and decision from the same events.
- Walk-forward: chronological train, tune, freeze, and evaluate process.
