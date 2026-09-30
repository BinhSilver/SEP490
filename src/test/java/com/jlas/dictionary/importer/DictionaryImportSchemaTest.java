package com.jlas.dictionary.importer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DictionaryImportSchemaTest {

    private static final Path MIGRATION =
            Path.of("sql", "migrations", "004_dictionary_import_metadata.sql");

    @Test
    void migrationCreatesImportTablesWithRequiredConstraints() throws Exception {
        assertTrue(Files.isRegularFile(MIGRATION), "dictionary import migration must exist");

        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:dictionary_import_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE vocabulary_entry (id BIGINT PRIMARY KEY)");
                for (String sql : migrationStatements(Files.readString(MIGRATION))) {
                    statement.execute(sql);
                }
            }

            assertEquals(3, count(connection,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name IN ('dictionary_source', "
                            + "'dictionary_import_batch', 'vocabulary_external_reference')"));
            assertEquals(2, count(connection,
                    "SELECT COUNT(*) FROM information_schema.table_constraints "
                            + "WHERE constraint_type = 'FOREIGN KEY' "
                            + "AND table_name = 'vocabulary_external_reference'"));
            assertEquals(1, count(connection,
                    "SELECT COUNT(*) FROM information_schema.table_constraints "
                            + "WHERE constraint_type = 'UNIQUE' "
                            + "AND table_name = 'vocabulary_external_reference'"));
            assertTrue(count(connection,
                    "SELECT COUNT(*) FROM information_schema.table_constraints "
                            + "WHERE constraint_type = 'CHECK' "
                            + "AND table_name = 'dictionary_import_batch'") >= 2);
        }
    }

    private static int count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static List<String> migrationStatements(String script) {
        StringBuilder withoutComments = new StringBuilder();
        for (String line : script.lines().toList()) {
            if (!line.stripLeading().startsWith("--")) {
                withoutComments.append(line).append('\n');
            }
        }
        List<String> statements = new ArrayList<>();
        for (String candidate : withoutComments.toString().split(";")) {
            String sql = candidate.strip();
            if (!sql.isEmpty()) {
                statements.add(sql);
            }
        }
        return statements;
    }
}
