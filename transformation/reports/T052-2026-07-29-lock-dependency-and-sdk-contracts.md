# Implementation Report - T052

## Summary

T052 makes dependency installation reproducible across the Python executor, Angular dashboard, and Maven build; detects JVM/sidecar response-shape drift through one versioned contract; and exposes fail-closed executor protocol and SDK capability evidence through the JVM runtime/preflight surface.

## Task

- Task ID: T052
- Task section: `transformation/tasks/PHASE-4-protocol-and-control-plane.md#t052`
- Branch: `task/T052-lock-dependency-sdk-contracts`
- PR: #16
- Status at completion: DONE

## Files changed

- `executor-python/pyproject.toml`
  - Pins the build backend, runtime dependencies, and test dependencies to exact versions.
- `executor-python/requirements.lock`
  - Commits the exact Python 3.12/Linux transitive dependency graph proven by CI.
- `executor-python/Dockerfile.smoke`
  - Installs exact pip and the committed lock before installing the executor with `--no-deps`; runs `pip check`.
- `.github/workflows/ci.yml`
  - Keys pip caching to both authority files, installs strictly from the lock, runs `pip check`, and adds dependency-authority validation to the static job.
- `scripts/ci/check_dependency_locks.py`
  - Rejects non-exact Python pins, lock drift/duplicates, npm package-manager or root-lock drift, Maven wrapper drift, and unstable Maven dependency versions.
- `contracts/executor-api-v1.properties`
  - Defines versioned exact field sets and Python required fields for executor response and capability models.
- `executor-python/tests/test_executor_api_contract.py`
  - Checks Pydantic schemas against the shared contract.
- `src/test/java/com/vokerg/voktrader/executor/ExecutorApiContractTest.java`
  - Checks Java response-record fields against the same contract.
- `executor-python/voktrader_executor/capability_identity.py`
  - Resolves installed executor and exchange SDK package versions from runtime package metadata.
- `executor-python/voktrader_executor/models.py`
  - Adds contract, executor, SDK, and capability-evidence fields to the authenticated capability response.
- `executor-python/tests/test_executor_capability_identity.py`
  - Proves the locked executor and SDK versions are reported by `/v1/capabilities`.
- `src/main/java/com/vokerg/voktrader/executor/ExecutorCapabilitiesResponse.java`
- `src/main/java/com/vokerg/voktrader/executor/ExecutorOrderVariation.java`
  - Model the versioned sidecar capability response in Java.
- `src/main/java/com/vokerg/voktrader/executor/PythonExecutorClient.java`
  - Fetches authenticated capability evidence with structured fail-closed errors.
- `src/main/java/com/vokerg/voktrader/executor/ExecutorCapabilityService.java`
  - Validates expected protocol version, SDK package, package versions, and FOK/FAK/GTC/GTD support; emits every blocker.
- `src/main/java/com/vokerg/voktrader/api/runtime/RuntimeStatusController.java`
  - Exposes the reusable capability report at `executor.capabilities` in `/api/runtime/status`.
- `src/test/java/com/vokerg/voktrader/executor/ExecutorCapabilityServiceTest.java`
  - Proves valid evidence passes and missing/drifted evidence fails closed with all blockers.
- `src/test/java/com/vokerg/voktrader/api/runtime/RuntimeStatusControllerTest.java`
  - Proves runtime status reports protocol and SDK capability evidence.
- `docs/ci.md`
  - Documents fresh-checkout commands, deterministic lock regeneration, contract evolution, and capability blockers.
- Transformation task ledger, index, and this report.

## Design decisions

- Use exact direct and transitive Python pins rather than allowing CI to resolve current-compatible versions.
- Pin pip and the setuptools build backend so installation mechanics do not float independently of the runtime graph.
- Retain npm semver declarations while treating `package-lock.json`, `npm@11.12.1`, and `npm ci` as the Angular authority.
- Treat the exact Maven wrapper distribution and exact Spring parent/properties as Maven authority; reject dynamic/range dependency versions.
- Use one language-neutral properties contract rather than duplicating field expectations in two test suites.
- Make additive response fields fail the contract deliberately; contract evolution must create and adopt a new explicit contract version.
- Resolve package identity from installed distribution metadata instead of source constants. Missing metadata reports `UNKNOWN`, sets capability evidence unavailable, and blocks compatibility.
- Keep capability evaluation in a reusable service so T045 can aggregate it into the complete live preflight without duplicating protocol logic.

## Tests run

GitHub Actions implementation run `30557695660` on head `ddbfe82e8f84adf8613a97db47b0506af8e9a4bc`.

- Locked Python install and `pip check`: passed.
- Python tests: 24 passed, including exact response-schema and installed capability-identity tests.
- `ExecutorApiContractTest`: 1 passed.
- `ExecutorCapabilityServiceTest`: 2 passed.
- `RuntimeStatusControllerTest`: 1 passed.
- Dependency-authority static check: passed.
- Clean PostgreSQL migration: passed.
- Angular tests and production build: passed.
- Secret scan: passed.
- Java-plus-executor compose smoke using the committed lock: passed.
- Full Java suite: 284 tests, 7 failures, 18 errors, 2 skipped.
  - All T052-specific tests pass.
  - Remaining failures/errors are the established architecture, runtime-state, and order-lifecycle baseline; no gate was suppressed.

## Safety impact

This task changes dependency reproducibility, adapter contract validation, and operator-readable capability evidence only. It does not enable live capital or change strategy, risk, fee, tick, order-routing, cancellation, reconciliation, settlement, or replay semantics. Missing or drifted executor capability evidence now fails closed instead of being assumed compatible.

## Backward compatibility

- Existing executor order/status/fill JSON fields are unchanged.
- `/v1/capabilities` adds identity and evidence fields under the explicitly versioned `executor-api-v1` contract.
- `/api/runtime/status` adds `executor.capabilities`; existing status fields remain unchanged.
- Runtime package versions resolve to the exact versions already proven by CI and the smoke image.
- Future response-shape additions or removals require an explicit contract-version update and coordinated Java/Python adoption.

## Remaining risks

- The committed Python transitive lock is verified for Python 3.12/Linux, the CI and smoke target. Any additional supported platform requires a separately reviewed lock/marker strategy and evidence.
- Package metadata is evaluated at sidecar process startup; dependency replacement requires a process restart before new identity evidence is reported.
- Existing Java lifecycle failures remain outside T052 and are not suppressed.

## Follow-up tasks

No new task is required. T045 can consume `ExecutorCapabilityService` when its other dependencies are complete. No task dependencies or ordering were changed.

## Completion checklist

- [x] Acceptance criteria met
- [x] Tests added/updated
- [x] Tests run and results recorded
- [x] Task section status updated
- [x] Task index updated
- [x] No unrelated strategy tuning included
