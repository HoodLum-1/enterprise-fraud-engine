package com.capitec.connect.fraudengine.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against JPA entity changes being made without a matching Flyway migration.
 * The main profile runs with ddl-auto=validate, so an unmapped schema change would
 * only surface at runtime against a real PostgreSQL database (tests use H2 with
 * ddl-auto=create-drop, which masks the drift).
 */
class SchemaMigrationDriftTest {

    private static final List<Class<?>> ENTITIES = List.of(
        TransactionEntity.class,
        FraudAlertEntity.class
    );

    @Test
    void everyEntityColumnIsCoveredByFlywayMigration() throws Exception {
        String ddl = Files.readString(
            Path.of(getClass().getClassLoader().getResource("db/migration/V1__init_schema.sql").toURI())
        ).toLowerCase();

        for (Class<?> entityClass : ENTITIES) {
            Table table = entityClass.getAnnotation(Table.class);
            String tableName = table.name().toLowerCase();
            assertTrue(ddl.contains("create table " + tableName),
                "No CREATE TABLE " + tableName + " found in Flyway migration");

            for (Field field : entityClass.getDeclaredFields()) {
                String columnName = resolveColumnName(field);
                assertTrue(ddl.contains(columnName.toLowerCase()),
                    "Column '" + columnName + "' (entity " + entityClass.getSimpleName()
                        + "." + field.getName() + ") is missing from the Flyway migration. "
                        + "Add a new V{n}__ migration instead of relying on hibernate ddl-auto.");
            }
        }
    }

    private static String resolveColumnName(Field field) {
        Column column = field.getAnnotation(Column.class);
        if (column != null && !column.name().isBlank()) {
            return column.name();
        }
        return field.getName();
    }
}
