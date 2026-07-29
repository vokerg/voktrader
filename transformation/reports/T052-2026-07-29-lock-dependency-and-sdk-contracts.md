# Implementation Report - T052

## Summary

Implementation is in progress. The first two slices establish committed dependency authority for Python, npm, and Maven tooling, and a shared executor API response-shape contract consumed by both Python and Java tests.

## Task

- Task ID: T052
- Task section: `transformation/tasks/PHASE-4-protocol-and-control-plane.md#t052`
- Branch: `task/T052-lock-dependency-sdk-contracts`
- PR: #16
- Status at completion: IN_PROGRESS

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
  - Defines the versioned exact field sets and Python required fields for executor response/capability models.
- `executor-python/tests/test_executor_api_contract.py`
  - Checks Pydantic schemas against the shared contract.
- `src/test/java/com/vokerg/voktrader/executor/ExecutorApiContractTest.java`
  - Checks Java response-record fields against the same contract.
- Transformation task ledger, index, and this report.

## Design decisions

- Use exact direct and transitive Python pins rather than allowing CI to resolve current-compatible versions.
- Pin pip and the setuptools build backend so installation mechanics do not float independently of the runtime graph.
- Retain npm semver declarations while treating `package-lock.json`, `npm@11.12.1`, and `npm ci` as the Angular authority.
- Treat the exact Maven wrapper distribution and exact Spring parent/properties as Maven authority; reject dynamic/range dependency versions.
- Use one language-neutral properties contract rather than duplicating field expectations in two test suites.
- Make additive response fields fail the contract deliberately; contract evolution must create and adopt a new explicit contract version.

## Tests run

GitHub Actions run `30474807607` on head `1f9acec52dbd535a16222b42733b94c783e5df5c`.

```bash
python scripts/ci/check_dependency_locks.py
```

Result:

```text
Dependency lock checks passed.
```

```bash
python -m pip install --upgrade pip==26.1.2
python -m pip install --requirement executor-python/requirements.lock
python -m pip install --no-deps --editable ./executor-python
python -m pip check
python -m pytest -q executor-python/tests
```

Result:

```text
Locked installation and pip check passed.
23 tests passed, including the shared Python response-schema contract test.
```

Java contract evidence:

```text
ExecutorApiContractTest: 1 passed, 0 failures, 0 errors.
Full Java suite: 282 tests, 7 failures, 18 errors, 2 skipped.
The remaining failures/errors are the established architecture, runtime-state, and order-lifecycle baseline.
```

Other passing jobs:

- Clean PostgreSQL migration
- Angular tests and production build
- Static repository/default-token and dependency-authority checks
- Secret scan
- Java-plus-executor compose smoke using the committed Python lock

## Safety impact

This task changes dependency reproducibility and adapter contract validation only so far. It does not enable live capital or change strategy, risk, fee, tick, order-routing, cancellation, reconciliation, or settlement semantics.

## Backward compatibility

- Runtime package versions now resolve to the exact versions already proven by the previous successful CI environment.
- Existing executor JSON fields are unchanged.
- Future response-shape additions or removals now require an explicit contract-version update and coordinated Java/Python adoption.

## Remaining risks

- SDK/protocol capability metadata still needs to be exposed through the JVM runtime/preflight surface.
- Lock regeneration procedure and local fresh-checkout commands still need final documentation.
- Python lock is currently verified for Python 3.12/Linux, the CI and smoke target; cross-platform markers require deliberate validation when adding another supported platform.
- Existing Java lifecycle failures remain outside T052 and are not suppressed.

## Follow-up tasks

None created yet. No ordering changes proposed.

## Completion checklist

- [ ] Acceptance criteria met
- [x] Tests added/updated
- [x] Tests run and results recorded for implemented slices
- [x] Task section status updated
- [x] Task index updated
- [x] No unrelated strategy tuning included
