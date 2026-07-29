# CI and local verification

The GitHub Actions workflow in `.github/workflows/ci.yml` runs for pull requests targeting `main` or `transformation/2.0`, and for pushes to those branches.

Run the same checks locally from the repository root.

## Java

Requires Java 22.

```bash
chmod +x mvnw
./mvnw -B -ntp test
```

## Python executor

Requires Python 3.11 or newer. CI uses Python 3.12.

```bash
python -m venv .venv
. .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -e ./executor-python pytest httpx
python -m pytest -q executor-python/tests
```

## Angular dashboard

Requires Node.js 22 and npm 11.12.1.

```bash
npm install --global npm@11.12.1
cd dashboard
npm ci
npm test -- --watch=false
npm run build
```

## Static and default-token checks

```bash
python scripts/ci/check_repository.py
python -m compileall -q executor-python/voktrader_executor
```

The repository check rejects unresolved merge markers, high-confidence secret formats, and unapproved default-token literals in live-capable configuration, workflow, compose, Docker, and executor source files. Gitleaks runs separately in GitHub Actions.

## Schema authority

Flyway is the production schema authority. The `live` profile explicitly enables migration validation, disables automatic baselining and cleaning, and sets `spring.jpa.hibernate.ddl-auto=validate`. A live startup therefore fails closed when migration history is missing, invalid, or inconsistent with the mapped entities; Hibernate cannot create or alter production tables.

The unprofiled `application.properties` file retains `spring.jpa.hibernate.ddl-auto=update` and `spring.flyway.baseline-on-migrate=true` only for the legacy local H2 development database. Treat that as a local-development compatibility override, never as a production policy. Do not use those values for PostgreSQL deployments or combine them with live operation.

New schema changes must be append-only versioned migrations under `src/main/resources/db/migration`. Do not edit an already-applied migration checksum or silently baseline an existing production schema.

## Clean PostgreSQL migration

Start an empty PostgreSQL database:

```bash
docker run --rm --name voktrader-ci-postgres \
  -e POSTGRES_DB=voktrader_ci \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  -d postgres:16-alpine
```

Then run the isolated Flyway migration test:

```bash
VOKTRADER_MIGRATION_TEST=true \
VOKTRADER_MIGRATION_JDBC_URL=jdbc:postgresql://localhost:5432/voktrader_ci \
VOKTRADER_MIGRATION_DB_USER=postgres \
VOKTRADER_MIGRATION_DB_PASSWORD=postgres \
./mvnw -B -ntp -Dtest=CleanDatabaseMigrationTest test
```

The test cleans the disposable database, migrates every script, validates migration checksums and naming, and runs `migrate` a second time to prove that no additional migration is executed.

Stop the local database when finished:

```bash
docker stop voktrader-ci-postgres
```

## Java-plus-sidecar smoke environment

The smoke environment is dry-run only. It disables Flyway in the Java container because schema migration is tested independently against PostgreSQL above.

```bash
bash scripts/ci/smoke-compose.sh
```

For manual inspection:

```bash
docker compose -f compose.smoke.yml up --build
```

The executor is exposed on `127.0.0.1:18099` and the Java app on `127.0.0.1:18080`.
