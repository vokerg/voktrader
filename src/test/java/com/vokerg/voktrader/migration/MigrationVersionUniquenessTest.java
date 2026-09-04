package com.vokerg.voktrader.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class MigrationVersionUniquenessTest {
    private static final Path MIGRATION_DIRECTORY = Path.of("src/main/resources/db/migration");
    private static final Pattern VERSIONED_MIGRATION = Pattern.compile("^V(.+?)__.+\\.sql$");

    @Test
    void versionedMigrationNamesAreValidAndNormalizedVersionsAreUnique() throws IOException {
        List<String> versionedFiles;
        try (Stream<Path> paths = Files.list(MIGRATION_DIRECTORY)) {
            versionedFiles = paths
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("V"))
                    .sorted()
                    .toList();
        }

        List<String> invalidNames = versionedFiles.stream()
                .filter(name -> !VERSIONED_MIGRATION.matcher(name).matches())
                .toList();
        assertThat(invalidNames)
                .as("versioned migrations must use V<version>__<description>.sql")
                .isEmpty();

        Map<String, List<String>> filesByNormalizedVersion = versionedFiles.stream()
                .collect(Collectors.groupingBy(
                        MigrationVersionUniquenessTest::normalizedVersion,
                        TreeMap::new,
                        Collectors.toList()));

        Map<String, List<String>> duplicates = filesByNormalizedVersion.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        TreeMap::new));

        assertThat(duplicates)
                .as("Flyway treats dots and underscores as version separators")
                .isEmpty();
    }

    private static String normalizedVersion(String fileName) {
        Matcher matcher = VERSIONED_MIGRATION.matcher(fileName);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid versioned migration name: " + fileName);
        }
        return matcher.group(1).replace('_', '.');
    }
}
