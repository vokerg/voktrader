# Voktrader Runbook

This runbook is the operational source of truth for run modes, strategy selection, backtests, and live safety.

## Strategy Namespaces

Voktrader has two strategy namespaces.

Top-level Spring/Java strategy IDs are registered in `StrategyRegistry`. These are valid bot `strategyId` values and valid `/api/backtests.strategyId` values. Examples:

- `strategy-v2`
- `resolution-pressure-fok`
- `order-book-liquidity`
- `maker-resolution-carry`
- `cost-aware-momentum`

Strategy V2 inner YAML strategy IDs live inside a Strategy V2 config file such as `strategy-v2.default.yml` or `strategy-v2.deep-research.yml`. Examples:

- `cfg_v2_liquidity_momentum`
- `ANTI_CHOP_FOK_A`
- `RP_FOK_B`

Use `strategyId=strategy-v2` to run the Java Strategy V2 engine. A bot can optionally set `strategySetId` to choose a whole Strategy V2 YAML bundle:

- `default` loads `strategy-v2.default.yml`
- `deep-research` loads `strategy-v2.deep-research.yml`

If `strategySetId` is blank, Strategy V2 uses the app-level imported config. After the YAML bundle is selected, the engine uses that bundle's `strategy-v2.engine.active-strategy-ids`.

`subStrategyId` is an advanced isolation/debug field. If set, it filters the selected YAML bundle down to one inner strategy. Leave it blank for normal portfolio-style operation.

Important: `cfg_v2_liquidity_momentum` is not a top-level `/api/backtests.strategyId`. It is a Strategy V2 inner ID.

## Manual Backtest

Start Spring Boot with runtime bots disabled:

```powershell
cd C:\repos\voktrader
$env:SPRING_PROFILES_ACTIVE="optimizer"
.\mvnw.cmd spring-boot:run
```

Run a legacy Java strategy backtest:

```powershell
curl.exe -X POST http://localhost:8080/api/backtests `
  -H "Content-Type: application/json" `
  -d '{"strategyId":"resolution-pressure-fok","marketIds":["2229611"]}'
```

Run a Strategy V2 backtest:

```powershell
curl.exe -X POST http://localhost:8080/api/backtests `
  -H "Content-Type: application/json" `
  -d '{"strategyId":"strategy-v2","marketIds":["2229611"]}'
```

`strategyYamlOverride` is supported only when `strategyId` is `strategy-v2`. It lets the request provide a temporary Strategy V2 YAML config for that backtest run.

Backtest market IDs must be numeric strings. Replay needs stored price snapshots and both Up and Down order-book depth rows for a tick to be usable.

## Paper Run

Paper mode is the safe default:

```powershell
cd C:\repos\voktrader
$env:SPRING_PROFILES_ACTIVE="paper"
.\mvnw.cmd spring-boot:run
```

On a fresh DB, the app seeds one default bot from `voktrader.strategy.active`. With an existing DB, runtime bots use persisted `bot_configs.strategy_id`; changing `voktrader.strategy.active` does not automatically update existing bots. For Strategy V2 bots, `bot_configs.strategy_set_id` can select the whole YAML bundle per bot.

Run all enabled bots:

```powershell
$env:SPRING_PROFILES_ACTIVE="paper"
Remove-Item Env:VOKTRADER_BOTS_INCLUDE_IDS -ErrorAction SilentlyContinue
.\mvnw.cmd spring-boot:run
```

Run one bot only:

```powershell
$env:SPRING_PROFILES_ACTIVE="paper"
$env:VOKTRADER_BOTS_INCLUDE_IDS="10"
.\mvnw.cmd spring-boot:run
```

## Live

Live requires two processes:

1. Python executor sidecar
2. Java Spring Boot app

Start the executor:

```powershell
cd C:\repos\voktrader\executor-python
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -e .
Copy-Item .env.example .env
uvicorn voktrader_executor.main:app --host 127.0.0.1 --port 8099
```

Required executor env vars:

- `EXECUTOR_API_TOKEN`
- `EXECUTOR_DRY_RUN=false`
- `POLYMARKET_PRIVATE_KEY`
- `POLYMARKET_FUNDER` when applicable
- Optional but recommended: `POLYMARKET_API_KEY`, `POLYMARKET_API_SECRET`, `POLYMARKET_API_PASSPHRASE`
- `MAX_ORDER_AMOUNT_USD`
- `REQUIRE_FOK=false`

Start Java:

```powershell
cd C:\repos\voktrader
$env:SPRING_PROFILES_ACTIVE="live,live-test"
$env:VOKTRADER_EXECUTOR_API_TOKEN="change-me"
.\mvnw.cmd spring-boot:run
```

Live safety gates:

- `voktrader.trading.mode=LIVE`
- `voktrader.trading.kill-switch-enabled=false`
- `voktrader.trading.live-enabled=true`
- `voktrader.trading.max-order-usd=1.00`
- `voktrader.trading.max-open-live-trades=1` unless deliberately raised
- `voktrader.trading.max-trades-per-market=1` unless deliberately raised
- `voktrader.trading.allowed-strategy-ids`
- `voktrader.executor.enabled=true`
- `voktrader.executor.dry-run=false`
- `voktrader.executor.base-url=http://127.0.0.1:8099`

Use `application-live-test.properties` for extra operator guardrails such as `voktrader.bots.include-ids` and live retry cooldown.

## Risk Gates

Authoritative Java risk gates are enforced by `RiskCheckService` and related execution services:

- Kill switch: `voktrader.trading.kill-switch-enabled`
- Live enabled flag: `voktrader.trading.live-enabled`
- Max order size: `voktrader.trading.max-order-usd`
- Max trades per market: `voktrader.trading.max-trades-per-market`
- Max open live trades: `voktrader.trading.max-open-live-trades`
- Strategy allowlist: `voktrader.trading.allowed-strategy-ids`
- Executor enabled/dry-run/auth settings: `voktrader.executor.*`

The old `voktrader.risk.enable-live-full` and `voktrader.risk.global-kill-switch` properties are not enforced by `RiskCheckService` and should not be treated as authoritative.

## Recommended Operational Matrix

| Mode | ExecutionMode | Strategy source | Sends real order? |
| --- | --- | --- | --- |
| Backtest | `BACKTEST` / replay context | Request `strategyId`; Strategy V2 may use YAML override | No |
| Paper | `PAPER` | Bot `strategy_id`; V2 YAML bundle from `strategy_set_id` or app import | No |
| Live | `LIVE` | Bot `strategy_id`; V2 YAML bundle from `strategy_set_id` or app import | Yes, through Python executor |

## Visibility Endpoints

- Runtime status: `GET /api/runtime/status`
- Strategy catalog: `GET /api/strategies`
- Bot configs: `GET /api/bots`
