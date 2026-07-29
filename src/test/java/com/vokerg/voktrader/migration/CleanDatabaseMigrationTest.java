package com.vokerg.voktrader.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "VOKTRADER_MIGRATION_TEST", matches = "true")
class CleanDatabaseMigrationTest {

    @Test
    void cleanPostgresDatabaseMigratesAndValidatesDeterministically() {
        Flyway flyway = Flyway.configure()
                .dataSource(
                        requiredEnvironment("VOKTRADER_MIGRATION_JDBC_URL"),
                        requiredEnvironment("VOKTRADER_MIGRATION_DB_USER"),
                        requiredEnvironment("VOKTRADER_MIGRATION_DB_PASSWORD"))
                .locations("classpath:db/migration")
                .validateMigrationNaming(true)
                .cleanDisabled(false)
                .load();

        flyway.clean();
        flyway.migrate();
        flyway.validate();

        MigrationInfo[] applied = flyway.info().applied();
        assertTrue(applied.length > 0, "Expected at least one applied migration");
        assertTrue(
                Arrays.stream(applied)
                        .anyMatch(info -> info.getVersion() != null && "2.1".equals(info.getVersion().toString())),
                "Expected the trade execution schema to be applied as version 2.1");
        assertEquals(0, flyway.info().pending().length, "Expected no pending migrations");
        assertEquals(0, flyway.migrate().migrationsExecuted, "A second migrate must be a no-op");
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for the migration test");
        }
        return value;
    }
}
