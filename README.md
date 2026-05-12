# Voktrader

Voktrader is a Spring Boot trading and research app for short Polymarket Up/Down markets. It records market data, runs paper and backtest strategies, manages runtime bot configs, and can route tiny live orders through a separate Python executor sidecar.

The safe default is `PAPER`: the kill switch is on, live trading is disabled, and the executor is disabled.

## Quick Start

Run the app in paper mode:

```powershell
cd C:\repos\voktrader
$env:SPRING_PROFILES_ACTIVE="paper"
.\mvnw.cmd spring-boot:run
```

Run a manual backtest with bots disabled:

```powershell
$env:SPRING_PROFILES_ACTIVE="optimizer"
.\mvnw.cmd spring-boot:run

curl.exe -X POST http://localhost:8080/api/backtests `
  -H "Content-Type: application/json" `
  -d '{"strategyId":"strategy-v2","marketIds":["2229611"]}'
```

Run live-shadow, which records live-like orders but does not send to Polymarket:

```powershell
$env:SPRING_PROFILES_ACTIVE="shadow"
.\mvnw.cmd spring-boot:run
```

Run live-tiny only after the Python executor is configured:

```powershell
cd C:\repos\voktrader\executor-python
.\.venv\Scripts\Activate.ps1
uvicorn voktrader_executor.main:app --host 127.0.0.1 --port 8099

cd C:\repos\voktrader
$env:SPRING_PROFILES_ACTIVE="live-tiny,live-test"
.\mvnw.cmd spring-boot:run
```

## Config Locations

- Main app config: `src/main/resources/application.properties`
- Profiles: `application-paper.properties`, `application-shadow.properties`, `application-live-tiny.properties`, `application-live-test.properties`, `application-optimizer.properties`
- Strategy V2 YAML: `src/main/resources/strategy-v2.paper.yml`, `src/main/resources/strategy-v2.deep-research.yml`, `src/main/resources/strategy-v2.example.yml`
- Per-bot Strategy V2 bundle selection: `bot_configs.strategy_config_id` (`paper` or `deep-research`)
- Python executor: `executor-python/`

See [docs/runbook.md](docs/runbook.md) for the operational source of truth.
