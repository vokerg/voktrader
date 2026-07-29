# Implementation Report - T050

## Summary

Added a repeatable GitHub Actions pipeline for Java, Python, Angular, PostgreSQL migration, repository safety checks, secret scanning, and a dry-run Java-plus-executor compose smoke environment.

The final head run proves that the workflow triggers for a pull request targeting `transformation/2.0`. Python, Angular, static/default-token, secret-scan, and compose-smoke jobs pass. The Java and clean-PostgreSQL jobs execute and retain artifacts, but correctly report existing branch debt: 25 Java failures/errors concentrated in the risk/order-lifecycle workstreams, and duplicate Flyway version `V2`. Those failures are not hidden or bypassed by T050.

T050 is complete because its acceptance criteria require the CI and clean-database test to run, not that T050 repair the schema and lifecycle defects those new gates expose. T051 is now eligible to repair Flyway authority, while existing P0 risk and order-lifecycle tasks own the Java failures.

## Task

- Task ID: T050
- Task section: `transformation/tasks/PHASE-4-protocol-and-control-plane.md#t050`
- Branch: `task/T050-ci-pipeline-smoke-compose`
- PR: #13
- Status at completion: DONE
- Completed: 2026-07-28T16:25:50Z

## Files changed

### CI and evidence

- `.github/workflows/ci.yml`
  - Runs on pull requests and pushes for `main` and `transformation/2.0`.
  - Adds Java, Python, Angular, clean-PostgreSQL migration, static, secret-scan, and compose-smoke jobs.
  - Uses read-only repository permissions, concurrency cancellation, runtime-native dependency caches, and failure-log artifacts.
- `.gitleaks.toml`
  - Extends the standard Gitleaks rules and explicitly allowlists only documented non-secret sentinel/source-preserved content.
- `scripts/ci/check_repository.py`
  - Rejects unresolved merge markers, high-confidence secret formats, and unapproved default-token literals in operational files.
- `src/test/java/com/vokerg/voktrader/migration/CleanDatabaseMigrationTest.java`
  - Cleans and migrates a real PostgreSQL service when the isolated migration-test environment is enabled.

### Smoke environment

- `Dockerfile.smoke`
- `executor-python/Dockerfile.smoke`
- `compose.smoke.yml`
- `scripts/ci/smoke-compose.sh`

The executor runs in dry-run mode. The Java service uses an in-memory H2 database, disables Flyway because PostgreSQL migration is tested separately, enables an empty restricted bot runtime, and probes the runtime-status endpoint after validating executor health and authenticated capabilities.

### Documentation

- `docs/ci.md`
  - Documents the default Java, Python, Angular, static, migration, and compose commands.
- `README.md`
  - Links the CI/local-verification guide.

### Test-fixture maintenance discovered by CI

- `dashboard/src/app/app.spec.ts`
  - Aligns the generated shell test with the current router-outlet template.
- `src/test/java/com/vokerg/voktrader/executor/PythonExecutorClientOrderManagementTest.java`
- `src/test/java/com/vokerg/voktrader/support/ScriptedExecutorClient.java`
  - Supply the current required `TickSizeService` dependency.
- `src/test/java/com/vokerg/voktrader/strategy/v2/StrategyV2ExitEvaluatorTest.java`
  - Exercises the current typed exit-builder boundary instead of removed routing plumbing.

### Program bookkeeping

- `transformation/tasks/PHASE-4-protocol-and-control-plane.md`
- `transformation/tasks/PHASE-5-simulation-honesty.md`
- `transformation/tasks/INDEX.md`
- `transformation/reports/T050-2026-07-28-add-ci-pipeline-and-smoke-compose.md`

## Design decisions

1. **Separate migration truth from application startup.** A dedicated PostgreSQL job runs Flyway against a cleaned schema. The compose smoke uses H2 and disables Flyway so a migration-history defect cannot mask whether the Java and Python processes can start and communicate.
2. **Dry-run-only smoke.** The executor has no exchange credentials, uses an explicit CI-only bearer token, and runs with dry-run enabled. The Java process also declares executor dry-run.
3. **Empty bot runtime rather than external market activity.** The optimizer smoke enables the `BotRuntimeManager` bean required by scheduled order infrastructure but restricts included bot IDs to `-1`, so no configured bot runtime or market feed is started.
4. **Safe, native caches.** Maven, pip, and npm caches use their official setup actions and repository dependency descriptors. No build outputs or credentials are cached.
5. **Current-tree secret scan plus explicit sentinel policy.** Gitleaks scans the checked-out tree with default rules. Known audit text and intentional non-secret sentinels are reviewed in `.gitleaks.toml`; operational default-token literals are independently checked by the dependency-free repository script.
6. **Retain failure evidence.** Java, migration, secret-scan, and compose logs/reports are uploaded even when their job fails, making baseline defects inspectable without rerunning CI.
7. **Do not repair unrelated runtime semantics.** CI-guided edits were limited to stale test fixtures and smoke configuration. The newly visible lifecycle, central-risk, and Flyway defects remain assigned to their existing transformation workstreams.

## Tests run

Final GitHub Actions run:

```text
https://github.com/vokerg/voktrader/actions/runs/30378027138
head: ee4a3f760429f4c1fb0dd1313e920603ba03872d
```

### Python executor

```bash
python -m pip install --upgrade pip
python -m pip install -e ./executor-python pytest httpx
python -m pytest -q executor-python/tests
```

Result: PASS.

### Angular dashboard

```bash
npm install --global npm@11.12.1
cd dashboard
npm ci
npm test -- --watch=false
npm run build
```

Result: PASS.

### Static/default-token checks

```bash
python scripts/ci/check_repository.py
python -m compileall -q executor-python/voktrader_executor
```

Result: PASS.

### Secret scan

```bash
docker run --rm -v "$PWD:/repo" zricethezav/gitleaks:v8.24.3 \
  detect --source=/repo --no-git --redact --exit-code=1 \
  --report-format=json --report-path=/repo/gitleaks-report.json
```

Result: PASS. The retained report contains no current-tree findings after the explicit non-secret allowlist is applied.

### Java-plus-executor compose smoke

```bash
bash scripts/ci/smoke-compose.sh
```

Result:

```text
executor authenticated capability probe passed
Java app is ready: http://127.0.0.1:18080/api/runtime/status
compose smoke passed
```

### Full Java suite

```bash
chmod +x mvnw
./mvnw -B -ntp test
```

Result: the gate runs and fails on existing transformation-branch debt.

```text
Tests run: 268, Failures: 7, Errors: 18, Skipped: 2
BUILD FAILURE
```

Representative evidence:

- `StrategyExecutionBoundaryArchitectureTest` still detects strategy dependence on disallowed execution plumbing.
- `StrategyV2RuntimeStateAwarenessTest` reports a partial-position exit interaction mismatch.
- `OrderLifecycleIntegrationTest` and `StrategyV2OrderLifecycleIntegrationTest` report missing/null lifecycle state across entry, partial-fill, resting-order, and exit scenarios.
- `OrderManagerTest` reports state/application mismatches.

These failures predate T050's infrastructure and are aligned with T012 and the durable order-lifecycle task chain. T050 does not suppress or rewrite them.

### Clean PostgreSQL migration

```bash
VOKTRADER_MIGRATION_TEST=true \
VOKTRADER_MIGRATION_JDBC_URL=jdbc:postgresql://localhost:5432/voktrader_ci \
VOKTRADER_MIGRATION_DB_USER=postgres \
VOKTRADER_MIGRATION_DB_PASSWORD=postgres \
./mvnw -B -ntp -Dtest=CleanDatabaseMigrationTest test
```

Result: the test connects to PostgreSQL 16, cleans the `public` schema, and then fails closed during Flyway resolution.

```text
Found more than one migration with version 2
V2__fake_signal_sells.sql
V2__trade_execution_model.sql
```

This is the schema-history defect T051 is intended to resolve; renumbering historical migrations was deliberately not folded into T050.

## Safety impact

- No live-capital profile, arming control, strategy threshold, fee, tick, entry, exit, cancellation, fill, reconciliation, or settlement behavior was changed.
- The smoke executor is dry-run only and has no exchange credentials.
- The smoke Java runtime is restricted to a nonexistent bot ID, preventing market tracking and strategy execution.
- New checks fail visibly on secret/default-token findings, invalid migration history, and safety/lifecycle regressions.

## Backward compatibility

- Production runtime artifacts and deployment configuration are unchanged; the new Dockerfiles and compose file are smoke-specific.
- Existing local commands continue to work. `docs/ci.md` adds canonical equivalents rather than replacing profile/runbook instructions.
- Test-fixture changes follow already-established production constructor and typed-intent contracts; they do not modify production behavior.
- CI checks are introduced as workflow jobs. Making them mandatory merge checks remains a repository branch-protection setting.

## Remaining risks

1. The clean PostgreSQL gate remains red until T051 resolves duplicate migration version `V2` and validates the full migration chain.
2. The Java gate remains red on 7 failures and 18 errors in central-risk and order-lifecycle behavior. Those failures should remain visible while T012 and the T020-T025 chain establish the intended contracts.
3. Repository administrators must select the desired CI jobs as required branch-protection checks; workflow code alone cannot enforce that setting.
4. Python dependency locking and broader Maven/npm contract enforcement remain T052 scope.

## Follow-up tasks

- T051 is moved from `BLOCKED` to `READY`; it owns deterministic Flyway authority and the duplicate-version migration blocker.
- T052 is moved from `BLOCKED` to `READY`; it owns dependency and SDK contract locking.
- T060 is moved from `BLOCKED` to `READY`; it can now use the CI evidence path for full-depth replay work.
- No new task was added. The Java failures map to existing T012 and order-lifecycle tasks, so creating a duplicate backlog item would fragment ownership.
- No tasks were reordered and no dependencies were changed.

## Completion checklist

- [x] Acceptance criteria met
- [x] Tests added/updated
- [x] Tests run and exact results recorded
- [x] Task section status updated
- [x] Task index updated
- [x] Dependent task statuses updated
- [x] No unrelated strategy tuning included
