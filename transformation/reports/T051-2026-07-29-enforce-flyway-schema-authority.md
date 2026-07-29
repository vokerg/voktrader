# Implementation Report - T051

## Summary

Implementation is in progress. The initial slice removes the duplicate Flyway version that blocked clean PostgreSQL migration, makes the live profile fail closed on schema drift, and adds deterministic migration and configuration guards.

## Task

- Task ID: T051
- Task section: `transformation/tasks/PHASE-4-protocol-and-control-plane.md#t051`
- Branch: `task/T051-flyway-schema-authority`
- PR: #15
- Status at completion: IN_PROGRESS

## Files changed

- `src/main/resources/db/migration/V2_1__trade_execution_model.sql`
  - Re-versions the newer trade execution schema after the historical legacy `V2` and before existing `V3+` migrations.
- `src/main/resources/db/migration/V2__trade_execution_model.sql`
  - Removed the duplicate-version filename.
- `src/main/resources/application-live.properties`
  - Enables Flyway validation, disables silent baselining and cleaning, and sets Hibernate DDL mode to `validate`.
- `src/test/java/com/vokerg/voktrader/migration/CleanDatabaseMigrationTest.java`
  - Validates naming and checksums and proves a second migrate executes zero migrations.
- `src/test/java/com/vokerg/voktrader/migration/MigrationVersionUniquenessTest.java`
  - Rejects invalid migration names and duplicate normalized versions without requiring a database container.
- `src/test/java/com/vokerg/voktrader/trade/LiveProfileConfigurationTest.java`
  - Verifies the live profile cannot use Hibernate schema mutation or automatic Flyway baselining.
- `docs/ci.md`
  - Documents production schema authority and the intentionally isolated legacy local H2 override.
- Transformation task ledger, index, and this report.

## Design decisions

- Preserve `V2__fake_signal_sells.sql` as the historical `V2` migration so databases that already recorded that checksum retain valid history.
- Assign the newer trade execution schema version `2.1`, placing it after legacy `V2` and before existing `V3+` migrations.
- Do not use Flyway repair, destructive migration, checksum mutation, or silent production baselining.
- Keep Flyway as the sole live/production schema authority and use Hibernate only to validate the migrated result.
- Retain the existing unprofiled H2 `ddl-auto=update` behavior only as a documented local-development compatibility override.

## Tests run

GitHub Actions run `30462458606` is queued for branch head `16dec75146ddaf85ebef277807adb8e03eb60b4b`.

Expected task-specific commands:

```bash
./mvnw -B -ntp -Dtest=MigrationVersionUniquenessTest,LiveProfileConfigurationTest test
```

```bash
VOKTRADER_MIGRATION_TEST=true \
VOKTRADER_MIGRATION_JDBC_URL=jdbc:postgresql://localhost:5432/voktrader_ci \
VOKTRADER_MIGRATION_DB_USER=postgres \
VOKTRADER_MIGRATION_DB_PASSWORD=postgres \
./mvnw -B -ntp -Dtest=CleanDatabaseMigrationTest test
```

Result: pending CI execution.

## Safety impact

This task changes schema-management controls and migration verification only. It does not enable live capital, alter strategy behavior, or change entry, exit, cancellation, settlement, fee, tick, or order-routing semantics. Live startup becomes stricter: invalid migration history or entity/schema drift blocks startup instead of allowing Hibernate to mutate tables.

## Backward compatibility

- Existing databases that recorded the legacy `V2__fake_signal_sells.sql` checksum retain that migration unchanged.
- The trade execution migration remains idempotent through `IF NOT EXISTS`, supporting local schemas previously created by Hibernate while bringing them under ordered Flyway history.
- A populated production database without valid Flyway history will now fail live startup instead of being silently baselined; operator review is required before migration.

## Remaining risks

- The queued clean PostgreSQL run may expose a later historical migration defect after the duplicate `V2` blocker is removed.
- Hibernate `validate` may expose entity/schema drift in existing operator databases; such drift must be resolved through append-only migrations rather than runtime repair.
- Compatibility with a real retained production `flyway_schema_history` table still requires operator evidence before merge if one exists outside CI.

## Follow-up tasks

None identified yet. Any newly exposed later migration defect will be handled in T051 if it is required for deterministic clean migration and can be repaired non-destructively; otherwise a dedicated follow-up task will be added.

## Completion checklist

- [ ] Acceptance criteria met
- [x] Tests added/updated
- [ ] Tests run and results recorded
- [x] Task section status updated
- [x] Task index updated
- [x] No unrelated strategy tuning included
