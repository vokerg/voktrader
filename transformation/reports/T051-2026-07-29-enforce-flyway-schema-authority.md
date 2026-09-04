# Implementation Report - T051

## Summary

Completed Flyway schema authority for live/production operation. Clean PostgreSQL now resolves, applies, validates, and re-runs the full migration chain deterministically. The live profile uses Flyway as the schema authority and Hibernate only for validation; it cannot silently update, baseline, or clean the production schema.

## Task

- Task ID: T051
- Task section: `transformation/tasks/PHASE-4-protocol-and-control-plane.md#t051`
- Branch: `task/T051-flyway-schema-authority`
- PR: #15
- Status at completion: DONE

## Files changed

- `src/main/resources/db/migration/V2_1__trade_execution_model.sql`
  - Re-versions the newer trade execution schema after the historical legacy `V2` and before existing `V3+` migrations.
- `src/main/resources/db/migration/V2_2__markets_baseline.sql`
  - Makes the previously Hibernate-created `markets` baseline explicit before lifecycle migrations use it.
- `src/main/resources/db/migration/V4_1__legacy_fake_signals_baseline.sql`
  - Reconstructs the empty legacy compatibility table required by V5-V8 without restoring retired runtime code.
- `src/main/resources/db/migration/V9_1__align_trade_tables_with_current_entities.sql`
- `src/main/resources/db/migration/V9_2__price_snapshots_baseline.sql`
- `src/main/resources/db/migration/V12_1__durable_order_identity_columns.sql`
- `src/main/resources/db/migration/V18_1__add_tick_size_metadata.sql`
- `src/main/resources/db/migration/V20_1__control_plane_audit_events.sql`
  - Remove duplicate normalized Flyway versions and establish formerly implicit Hibernate-owned tables/columns before first use.
- Removed duplicate filenames:
  - `V2__trade_execution_model.sql`
  - `V9__align_trade_tables_with_current_entities.sql`
  - `V18__add_tick_size_metadata.sql`
  - `V20__control_plane_audit_events.sql`
- `V11__strategy_backtest_runs.sql`, `V14__trade_enum_columns_to_varchar.sql`, `V19__trade_fill_remote_key.sql`
  - Replace H2-only `ALTER COLUMN ... VARCHAR(...)` syntax with PostgreSQL-compatible `SET DATA TYPE` syntax.
- `src/main/resources/application-live.properties`
  - Enables Flyway validation, disables silent baselining and cleaning, and sets Hibernate DDL mode to `validate`.
- `src/test/java/com/vokerg/voktrader/migration/CleanDatabaseMigrationTest.java`
  - Validates naming/checksums, prints the resolved plan, and proves a second migrate executes zero migrations.
- `src/test/java/com/vokerg/voktrader/migration/MigrationVersionUniquenessTest.java`
  - Rejects invalid migration names and duplicate normalized versions without a database container.
- `src/test/java/com/vokerg/voktrader/trade/LiveProfileConfigurationTest.java`
  - Verifies live cannot use Hibernate schema mutation or automatic Flyway baselining/cleaning.
- `src/test/java/com/vokerg/voktrader/trade/TradeOrderPersistenceInvariantMigrationTest.java`
  - Requires the portable V19 column-widening statement.
- `docs/ci.md`
  - Documents production schema authority and the intentionally isolated legacy local H2 override.
- Transformation task ledger, index, and this report.

## Design decisions

- Preserve `V2__fake_signal_sells.sql` as the historical V2 authority and move the newer trade schema to version `2.1`.
- Resolve all other duplicate normalized versions by moving the later additive migration to a decimal version while preserving its SQL body and order.
- Convert tables and columns that earlier migrations assumed Hibernate had created into explicit, ordered Flyway baselines.
- Keep the retired legacy signal baseline empty and compatibility-only; no Java runtime mapping or strategy behavior is restored.
- Use portable PostgreSQL/H2 type-change syntax where historical SQL was invalid on PostgreSQL.
- Do not use destructive repair, silent baselining, or disabled validation to make CI green.
- Keep Flyway as the sole live schema authority and use Hibernate only to validate the migrated result.
- Retain unprofiled H2 `ddl-auto=update` only as a documented local-development compatibility override.

## Tests run

Final implementation evidence before bookkeeping: GitHub Actions run `30473175117`, branch head `d9c2b88946042f3fabc382726b4bde7750b4709c`.

```bash
VOKTRADER_MIGRATION_TEST=true \
VOKTRADER_MIGRATION_JDBC_URL=jdbc:postgresql://localhost:5432/voktrader_ci \
VOKTRADER_MIGRATION_DB_USER=postgres \
VOKTRADER_MIGRATION_DB_PASSWORD=postgres \
./mvnw -B -ntp -Dtest=CleanDatabaseMigrationTest test
```

Result:

```text
31 migrations resolved and validated.
31 migrations applied to clean PostgreSQL; final version 23.
0 pending migrations.
Second migrate: no migration necessary.
Tests run: 1, failures: 0, errors: 0, skipped: 0.
```

Task-specific tests observed in the Java job:

```text
MigrationVersionUniquenessTest: 1 passed.
LiveProfileConfigurationTest: 2 passed.
TradeOrderPersistenceInvariantMigrationTest: final syntax assertion updated on the bookkeeping head.
```

Passing CI jobs on run `30473175117`:

- Clean PostgreSQL migration
- Python tests
- Angular tests and production build
- Static repository/default-token checks
- Secret scan
- Java-plus-executor compose smoke

The full Java suite remained red with the established architecture/runtime-state/order-lifecycle debt. Before the final assertion update it reported 281 tests, 8 failures, 18 errors, and 2 skipped; one failure was the T051 V19 assertion and was corrected on the final bookkeeping head. The remaining expected baseline is 7 failures and 18 errors.

## Safety impact

This task changes schema-management controls and migration verification only. It does not enable live capital, alter strategy behavior, or change entry, exit, cancellation, settlement, fee, tick, or order-routing semantics. Live startup is stricter: invalid migration history or entity/schema drift blocks startup instead of allowing Hibernate to mutate tables.

## Backward compatibility

- Existing databases that recorded `V2__fake_signal_sells.sql` retain that migration and checksum unchanged.
- Later duplicate migrations are assigned deterministic decimal versions while retaining their SQL bodies.
- The compatibility baselines use `IF NOT EXISTS`, so schemas previously materialized by Hibernate are not recreated destructively.
- V11, V14, and V19 had invalid PostgreSQL syntax and therefore required checksum-changing corrections. Any retained database that already recorded those migrations must be validated and, when appropriate, repaired explicitly by an operator before live startup; the live profile will not auto-baseline or ignore the mismatch.
- A populated production database without valid Flyway history now fails live startup instead of being silently baselined.

## Remaining risks

- A real retained operator database may have Flyway checksum/history differences from the clean CI chain. It must be reviewed using Flyway validation before deployment; no automatic repair is performed.
- The default unprofiled H2 development database still permits Hibernate update for legacy local convenience. It is documented and is not inherited by the live profile.
- Existing Java lifecycle failures remain outside T051 and are not hidden or weakened by this task.

## Follow-up tasks

No new task was required. T052 remains the next eligible non-excluded task after T051 merges. No dependencies were reordered.

## Completion checklist

- [x] Acceptance criteria met
- [x] Tests added/updated
- [x] Tests run and results recorded
- [x] Task section status updated
- [x] Task index updated
- [x] No unrelated strategy tuning included
