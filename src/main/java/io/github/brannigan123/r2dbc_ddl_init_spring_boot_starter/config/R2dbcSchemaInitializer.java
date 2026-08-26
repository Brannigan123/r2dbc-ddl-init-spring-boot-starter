package io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.config;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.data.relational.core.mapping.RelationalPersistentEntity;
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty;

import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.annotation.ColumnDefault;
import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.annotation.ForeignKey;
import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.annotation.Index;
import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.annotation.JsonColumn;
import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.annotation.NumericPrecisionScale;
import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.annotation.SearchIndex;
import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.annotation.SearchableField;
import io.r2dbc.postgresql.codec.Interval;
import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.Blob;
import tools.jackson.databind.JsonNode;

/**
 * Reflects over R2DBC relational entities on startup and creates or
 * synchronizes their tables, columns, indexes, check constraints and foreign
 * keys.
 */
public class R2dbcSchemaInitializer implements ApplicationRunner {

    private final R2dbcEntityTemplate entityTemplate;
    private final RelationalMappingContext mappingContext;

    public R2dbcSchemaInitializer(R2dbcEntityTemplate entityTemplate, RelationalMappingContext mappingContext) {
        this.entityTemplate = entityTemplate;
        this.mappingContext = mappingContext;
    }

    private record ColumnMetaData(
            String name,
            String dataType,
            String udtName,
            boolean isNullable,
            String columnDefault) {
    }

    private record PhysicalProperty(
            RelationalPersistentProperty property,
            String columnName,
            boolean isPrimaryKey) {
    }

    private record SearchColumnSpec(
            String columnName,
            String columnDdl,
            String indexDdl) {
    }

    @Override
    public void run(@NonNull ApplicationArguments args) {
        Set<Class<?>> embeddedTypes = findEmbeddedTypes();

        // Phase 1: Create all tables, update missing or modified columns, build
        // indexes, and set up check constraints
        for (RelationalPersistentEntity<?> entity : mappingContext.getPersistentEntities()) {
            if (embeddedTypes.contains(entity.getType())) {
                continue;
            }

            String tableName = entity.getTableName().getReference().toLowerCase();

            createTableIfNotExists(entity, tableName);
            synchronizeTableColumns(entity, tableName);
            createIndexes(entity, tableName);
            createEnumCheckConstraints(entity, tableName);
        }

        // Phase 2: Create foreign key constraints strictly after all referenced
        // entities exist
        for (RelationalPersistentEntity<?> entity : mappingContext.getPersistentEntities()) {
            if (embeddedTypes.contains(entity.getType())) {
                continue;
            }

            String tableName = entity.getTableName().getReference().toLowerCase();

            createForeignKeys(entity, tableName);
        }
    }

    private Set<Class<?>> findEmbeddedTypes() {
        Set<Class<?>> embeddedTypes = new HashSet<>();
        for (RelationalPersistentEntity<?> entity : mappingContext.getPersistentEntities()) {
            for (RelationalPersistentProperty property : entity) {
                if (property.isEmbedded()) {
                    embeddedTypes.add(Objects.requireNonNull(property.getTypeInformation().getActualType()).getType());
                }
            }
        }
        return embeddedTypes;
    }

    private List<PhysicalProperty> getPhysicalProperties(RelationalPersistentEntity<?> entity) {
        List<PhysicalProperty> properties = new ArrayList<>();
        collectPhysicalProperties(entity, false, properties);
        return properties;
    }

    private void collectPhysicalProperties(
            RelationalPersistentEntity<?> entity,
            boolean parentIsId,
            List<PhysicalProperty> properties) {
        for (RelationalPersistentProperty property : entity) {
            boolean isId = property.isIdProperty() || parentIsId;

            if (property.isEmbedded()) {
                RelationalPersistentEntity<?> embeddedEntity = mappingContext.getPersistentEntity(
                        Objects.requireNonNull(property.getTypeInformation().getActualType()));
                if (embeddedEntity != null) {
                    collectPhysicalProperties(embeddedEntity, isId, properties);
                }
            } else {
                String columnName = property.getColumnName().getReference().toLowerCase();
                properties.add(new PhysicalProperty(property, columnName, isId));
            }
        }
    }

    private SearchColumnSpec buildSearchColumnSpec(RelationalPersistentEntity<?> entity, String tableName) {
        PhysicalProperty searchPhysProp = null;
        SearchIndex searchIndexAnno = null;

        for (PhysicalProperty physProp : getPhysicalProperties(entity)) {
            Field field = physProp.property().getField();
            if (field != null && field.isAnnotationPresent(SearchIndex.class)) {
                searchPhysProp = physProp;
                searchIndexAnno = field.getAnnotation(SearchIndex.class);
                break;
            }
        }

        if (searchPhysProp == null || searchIndexAnno == null) {
            return null;
        }

        List<String> expressions = new ArrayList<>();
        for (PhysicalProperty physProp : getPhysicalProperties(entity)) {
            Field field = physProp.property().getField();
            if (field != null && field.isAnnotationPresent(SearchableField.class)) {
                SearchableField searchableField = field.getAnnotation(SearchableField.class);
                String columnName = physProp.columnName();
                String weight = searchableField.weight().getCode();
                String config = searchIndexAnno.config();

                expressions.add(String.format("setweight(to_tsvector('%s', coalesce(%s, '')), '%s')",
                        config, columnName, weight));
            }
        }

        if (expressions.isEmpty()) {
            throw new IllegalStateException("Entity " + entity.getType().getName()
                    + " contains @SearchIndex on column '" + searchPhysProp.columnName()
                    + "' but no fields annotated with @SearchableField.");
        }

        String searchColumnName = searchPhysProp.columnName();
        String vectorExpression = String.join(" || ' ' || ", expressions);
        String columnDdl = String.format("%s tsvector GENERATED ALWAYS AS (%s) STORED", searchColumnName, vectorExpression);

        String indexName = "idx_" + tableName + "_" + searchColumnName;
        String indexDdl = String.format("CREATE INDEX IF NOT EXISTS %s ON %s USING gin (%s);", indexName, tableName, searchColumnName);

        return new SearchColumnSpec(searchColumnName, columnDdl, indexDdl);
    }

    private void createTableIfNotExists(RelationalPersistentEntity<?> entity, String tableName) {
        List<PhysicalProperty> properties = getPhysicalProperties(entity);

        List<String> pkColumnNames = new ArrayList<>();
        for (PhysicalProperty physProp : properties) {
            if (physProp.isPrimaryKey()) {
                pkColumnNames.add(physProp.columnName());
            }
        }

        boolean isCompositeKey = pkColumnNames.size() > 1;
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                .append(tableName)
                .append(" (");

        SearchColumnSpec searchSpec = buildSearchColumnSpec(entity, tableName);

        boolean first = true;
        for (PhysicalProperty physProp : properties) {
            RelationalPersistentProperty property = physProp.property();
            String columnName = physProp.columnName();

            if (!first) {
                sql.append(", ");
            }

            if (searchSpec != null && searchSpec.columnName().equals(columnName)) {
                sql.append(searchSpec.columnDdl());
                first = false;
                continue;
            }

            sql.append(columnName)
                    .append(" ")
                    .append(mapToSqlType(property));

            String customDefault = getTargetDefaultValue(property, physProp.isPrimaryKey());
            if (customDefault != null) {
                sql.append(" DEFAULT ").append(customDefault);
            }

            if (isNonNull(property) && !physProp.isPrimaryKey()) {
                sql.append(" NOT NULL");
            }

            if (property.getType().isEnum()) {
                String constraintName = "chk_" + tableName + "_" + columnName;
                sql.append(" CONSTRAINT ")
                        .append(constraintName)
                        .append(buildEnumCheckClause(property, columnName));
            }

            if (physProp.isPrimaryKey() && !isCompositeKey) {
                sql.append(" PRIMARY KEY");
                if (isAutoIncrementType(property.getType())) {
                    sql.append(" GENERATED BY DEFAULT AS IDENTITY");
                }
            }

            first = false;
        }

        if (isCompositeKey) {
            sql.append(", PRIMARY KEY (")
                    .append(String.join(", ", pkColumnNames))
                    .append(")");
        }

        sql.append(");");

        entityTemplate.getDatabaseClient()
                .sql(sql.toString())
                .then()
                .block();

        if (searchSpec != null) {
            entityTemplate.getDatabaseClient()
                    .sql(searchSpec.indexDdl())
                    .then()
                    .block();
        }
    }

    private void synchronizeTableColumns(RelationalPersistentEntity<?> entity, String tableName) {
        List<ColumnMetaData> columnMetaList = entityTemplate.getDatabaseClient()
                .sql("""
                            SELECT column_name, data_type, udt_name, is_nullable, column_default
                            FROM information_schema.columns
                            WHERE table_name = :tableName
                        """)
                .bind("tableName", tableName)
                .map((row, metadata) -> new ColumnMetaData(
                        row.get("column_name", String.class),
                        row.get("data_type", String.class),
                        row.get("udt_name", String.class),
                        "YES".equalsIgnoreCase(row.get("is_nullable", String.class)),
                        row.get("column_default", String.class)))
                .all()
                .collectList()
                .block();

        Map<String, ColumnMetaData> existingColumns = new HashMap<>();
        if (columnMetaList != null) {
            for (ColumnMetaData col : columnMetaList) {
                existingColumns.put(col.name().toLowerCase(), col);
            }
        }

        SearchColumnSpec searchSpec = buildSearchColumnSpec(entity, tableName);

        for (PhysicalProperty physProp : getPhysicalProperties(entity)) {
            RelationalPersistentProperty property = physProp.property();
            String columnName = physProp.columnName();

            if (searchSpec != null && searchSpec.columnName().equals(columnName)) {
                if (!existingColumns.containsKey(columnName)) {
                    String alterSql = String.format("ALTER TABLE %s ADD COLUMN %s;", tableName, searchSpec.columnDdl());
                    entityTemplate.getDatabaseClient()
                            .sql(alterSql)
                            .then()
                            .block();

                    entityTemplate.getDatabaseClient()
                            .sql(searchSpec.indexDdl())
                            .then()
                            .block();
                }
                continue;
            }

            String targetType = mapToSqlType(property);
            boolean targetNonNull = isNonNull(property) && !physProp.isPrimaryKey();
            String targetDefault = getTargetDefaultValue(property, physProp.isPrimaryKey());

            if (!existingColumns.containsKey(columnName)) {
                StringBuilder sql = new StringBuilder("ALTER TABLE ")
                        .append(tableName)
                        .append(" ADD COLUMN ")
                        .append(columnName)
                        .append(" ")
                        .append(targetType);

                if (targetDefault != null) {
                    sql.append(" DEFAULT ").append(targetDefault);
                }

                if (targetNonNull) {
                    sql.append(" NOT NULL");
                }

                if (property.getType().isEnum()) {
                    String constraintName = "chk_" + tableName + "_" + columnName;
                    sql.append(" CONSTRAINT ")
                            .append(constraintName)
                            .append(buildEnumCheckClause(property, columnName));
                }

                entityTemplate.getDatabaseClient()
                        .sql(sql.toString())
                        .then()
                        .block();
            } else {
                ColumnMetaData current = existingColumns.get(columnName);

                // 1. Data Type Modification
                if (isTypeMismatched(targetType, current.dataType(), current.udtName())) {
                    String alterTypeSql = String.format(
                            "ALTER TABLE %s ALTER COLUMN %s TYPE %s USING %s::%s;",
                            tableName, columnName, targetType, columnName, targetType);
                    entityTemplate.getDatabaseClient()
                            .sql(alterTypeSql)
                            .then()
                            .block();
                }

                // 2. Nullability Modification
                if (targetNonNull && current.isNullable()) {
                    String setNotNullSql = String.format(
                            "ALTER TABLE %s ALTER COLUMN %s SET NOT NULL;",
                            tableName, columnName);
                    entityTemplate.getDatabaseClient()
                            .sql(setNotNullSql)
                            .then()
                            .block();
                } else if (!targetNonNull && !current.isNullable() && !physProp.isPrimaryKey()) {
                    String dropNotNullSql = String.format(
                            "ALTER TABLE %s ALTER COLUMN %s DROP NOT NULL;",
                            tableName, columnName);
                    entityTemplate.getDatabaseClient()
                            .sql(dropNotNullSql)
                            .then()
                            .block();
                }

                // 3. Default Value Modification
                if (targetDefault != null && !isDefaultMatching(targetDefault, current.columnDefault())) {
                    String setDefaultSql = String.format(
                            "ALTER TABLE %s ALTER COLUMN %s SET DEFAULT %s;",
                            tableName, columnName, targetDefault);
                    entityTemplate.getDatabaseClient()
                            .sql(setDefaultSql)
                            .then()
                            .block();
                } else if (targetDefault == null && current.columnDefault() != null
                        && !isIdentityDefault(current.columnDefault())) {
                    String dropDefaultSql = String.format(
                            "ALTER TABLE %s ALTER COLUMN %s DROP DEFAULT;",
                            tableName, columnName);
                    entityTemplate.getDatabaseClient()
                            .sql(dropDefaultSql)
                            .then()
                            .block();
                }
            }
        }
    }

    private void createEnumCheckConstraints(RelationalPersistentEntity<?> entity, String tableName) {
        for (PhysicalProperty physProp : getPhysicalProperties(entity)) {
            RelationalPersistentProperty property = physProp.property();
            if (property.getType().isEnum()) {
                String columnName = physProp.columnName();
                String constraintName = "chk_" + tableName + "_" + columnName;
                String checkClause = buildEnumCheckClause(property, columnName);

                String sql = String.format(
                        "DO $$ BEGIN " +
                                "IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = '%s') THEN " +
                                "ALTER TABLE %s ADD CONSTRAINT %s%s; " +
                                "END IF; END $$;",
                        constraintName, tableName, constraintName, checkClause);

                entityTemplate.getDatabaseClient()
                        .sql(sql)
                        .then()
                        .block();
            }
        }
    }

    private void createIndexes(RelationalPersistentEntity<?> entity, String tableName) {
        Class<?> entityClass = entity.getType();
        Index[] classIndexes = entityClass.getAnnotationsByType(Index.class);

        for (Index index : classIndexes) {
            if (index.columns().length > 0) {
                createIndex(tableName, index, index.columns());
            }
        }

        for (PhysicalProperty physProp : getPhysicalProperties(entity)) {
            RelationalPersistentProperty property = physProp.property();
            Field field = property.getField();
            if (field != null) {
                Index[] fieldIndexes = field.getAnnotationsByType(Index.class);
                for (Index index : fieldIndexes) {
                    String[] cols = index.columns().length > 0
                            ? index.columns()
                            : new String[] { physProp.columnName() };
                    createIndex(tableName, index, cols);
                }
            }
        }
    }

    private void createIndex(String tableName, Index index, String[] columns) {
        String indexName = index.name().isEmpty()
                ? "idx_" + tableName + "_" + String.join("_", columns)
                : index.name();
        String uniqueKeyword = index.unique() ? "UNIQUE " : "";
        String columnList = String.join(", ", columns);

        String sql = String.format(
                "CREATE %sINDEX IF NOT EXISTS %s ON %s (%s);",
                uniqueKeyword, indexName, tableName, columnList);

        entityTemplate.getDatabaseClient()
                .sql(sql)
                .then()
                .block();
    }

    private void createForeignKeys(RelationalPersistentEntity<?> entity, String tableName) {
        Class<?> entityClass = entity.getType();

        // 1. Class-level @ForeignKey annotations (recommended for composite foreign
        // keys)
        ForeignKey[] classForeignKeys = entityClass.getAnnotationsByType(ForeignKey.class);
        for (ForeignKey foreignKey : classForeignKeys) {
            String[] localCols = foreignKey.columns();
            String[] refCols = foreignKey.referencedColumns().length > 0
                    ? foreignKey.referencedColumns()
                    : (!foreignKey.column().isEmpty() ? new String[] { foreignKey.column() } : new String[0]);

            if (localCols.length > 0 && refCols.length > 0) {
                applyForeignKeyConstraint(tableName, foreignKey, localCols, refCols);
            }
        }

        // 2. Field-level @ForeignKey annotations (single or specified multi-column)
        for (PhysicalProperty physProp : getPhysicalProperties(entity)) {
            RelationalPersistentProperty property = physProp.property();
            Field field = property.getField();
            if (field != null) {
                ForeignKey[] fieldForeignKeys = field.getAnnotationsByType(ForeignKey.class);
                for (ForeignKey foreignKey : fieldForeignKeys) {
                    String[] localCols = foreignKey.columns().length > 0
                            ? foreignKey.columns()
                            : new String[] { physProp.columnName() };

                    String[] refCols = foreignKey.referencedColumns().length > 0
                            ? foreignKey.referencedColumns()
                            : (!foreignKey.column().isEmpty() ? new String[] { foreignKey.column() } : new String[0]);

                    if (localCols.length > 0 && refCols.length > 0) {
                        applyForeignKeyConstraint(tableName, foreignKey, localCols, refCols);
                    }
                }
            }
        }
    }

    private void applyForeignKeyConstraint(
            String tableName,
            ForeignKey foreignKey,
            String[] localCols,
            String[] refCols) {

        String constraintName = foreignKey.name().isEmpty()
                ? "fk_" + tableName + "_" + String.join("_", localCols)
                : foreignKey.name();

        String localColumnList = String.join(", ", localCols);
        String refColumnList = String.join(", ", refCols);

        String sql = String.format(
                "DO $$ BEGIN " +
                        "IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = '%s') THEN " +
                        "ALTER TABLE %s ADD CONSTRAINT %s FOREIGN KEY (%s) REFERENCES %s(%s) ON DELETE %s; " +
                        "END IF; END $$;",
                constraintName, tableName, constraintName, localColumnList, foreignKey.table(),
                refColumnList, foreignKey.onDelete().getAction());

        entityTemplate.getDatabaseClient()
                .sql(sql)
                .then()
                .block();
    }

    private String getTargetDefaultValue(RelationalPersistentProperty property, boolean isPrimaryKey) {
        Field field = property.getField();
        if (field != null && field.isAnnotationPresent(ColumnDefault.class)) {
            return field.getAnnotation(ColumnDefault.class).value();
        }
        if (isPrimaryKey) {
            if (property.getType().equals(String.class)) {
                return "gen_random_uuid()::text";
            } else if (property.getType().equals(UUID.class)) {
                return "gen_random_uuid()";
            }
        }
        return null;
    }

    private boolean isNonNull(RelationalPersistentProperty property) {
        if (property.getType().isPrimitive()) {
            return true;
        }

        Field field = property.getField();
        if (field == null) {
            return false;
        }

        for (Annotation annotation : field.getAnnotations()) {
            String name = annotation.annotationType().getSimpleName();
            if ("Nonnull".equalsIgnoreCase(name)
                    || "NonNull".equalsIgnoreCase(name)
                    || "NotNull".equalsIgnoreCase(name)) {
                return true;
            }
        }

        return false;
    }

    private boolean isTypeMismatched(String targetSqlType, String dbDataType, String dbUdtName) {
        String normalizedTarget = targetSqlType.toLowerCase().replaceAll("\\s+", "");
        String normalizedDbDataType = dbDataType != null ? dbDataType.toLowerCase().replaceAll("\\s+", "") : "";
        String normalizedDbUdt = dbUdtName != null ? dbUdtName.toLowerCase().replaceAll("\\s+", "") : "";

        if (normalizedTarget.startsWith("varchar")) {
            return !normalizedDbDataType.equals("character varying") && !normalizedDbDataType.equals("varchar");
        }
        if (normalizedTarget.startsWith("numeric")) {
            return !normalizedDbDataType.equals("numeric") && !normalizedDbUdt.equals("numeric");
        }
        if (normalizedTarget.equals("jsonb")) {
            return !normalizedDbUdt.equals("jsonb");
        }
        if (normalizedTarget.equals("bytea")) {
            return !normalizedDbDataType.equals("bytea") && !normalizedDbUdt.equals("bytea");
        }
        if (normalizedTarget.equals("interval")) {
            return !normalizedDbDataType.equals("interval") && !normalizedDbUdt.equals("interval");
        }
        if (normalizedTarget.equals("timestamptz")) {
            return !normalizedDbDataType.contains("timestamp") || !normalizedDbDataType.contains("with time zone");
        }
        if (normalizedTarget.equals("timestamp")) {
            return !normalizedDbDataType.contains("timestamp") || normalizedDbDataType.contains("with time zone");
        }
        if (normalizedTarget.equals("uuid")) {
            return !normalizedDbUdt.equals("uuid");
        }
        if (normalizedTarget.equals("tsvector")) {
            return !normalizedDbDataType.equals("tsvector") && !normalizedDbUdt.equals("tsvector");
        }
        if (normalizedTarget.equals("bigint")) {
            return !normalizedDbDataType.equals("bigint");
        }
        if (normalizedTarget.equals("int") || normalizedTarget.equals("integer")) {
            return !normalizedDbDataType.equals("integer");
        }
        if (normalizedTarget.equals("boolean")) {
            return !normalizedDbDataType.equals("boolean");
        }

        return !normalizedDbDataType.equals(normalizedTarget) && !normalizedDbUdt.equals(normalizedTarget);
    }

    private boolean isDefaultMatching(String targetDefault, String dbDefault) {
        if (dbDefault == null) {
            return false;
        }
        String cleanTarget = targetDefault.toLowerCase().replaceAll("\\s+|::[a-z_]+", "");
        String cleanDb = dbDefault.toLowerCase().replaceAll("\\s+|::[a-z_]+|'|\\(\\)", "");
        return cleanDb.contains(cleanTarget) || cleanTarget.contains(cleanDb);
    }

    private boolean isIdentityDefault(String dbDefault) {
        return dbDefault.toLowerCase().contains("nextval") || dbDefault.toLowerCase().contains("identity");
    }

    private String buildEnumCheckClause(RelationalPersistentProperty property, String columnName) {
        Object[] enumConstants = property.getType().getEnumConstants();
        if (enumConstants == null || enumConstants.length == 0) {
            return "";
        }
        String allowedValues = Arrays.stream(enumConstants)
                .map(constant -> "'" + ((Enum<?>) constant).name() + "'")
                .collect(Collectors.joining(", "));

        return " CHECK (" + columnName + " IN (" + allowedValues + "))";
    }

    private boolean isAutoIncrementType(Class<?> type) {
        return type.equals(Integer.class) || type.equals(int.class)
                || type.equals(Long.class) || type.equals(long.class)
                || type.equals(BigInteger.class);
    }

    private String mapToSqlType(RelationalPersistentProperty property) {
        Class<?> type = property.getType();
        Field field = property.getField();

        if (field != null && field.isAnnotationPresent(SearchIndex.class)) {
            return "TSVECTOR";
        } else if (field != null && field.isAnnotationPresent(JsonColumn.class)) {
            return "JSONB";
        } else if (Json.class.isAssignableFrom(type) || JsonNode.class.isAssignableFrom(type)) {
            return "JSONB";
        } else if (type.equals(byte[].class) || ByteBuffer.class.isAssignableFrom(type)
                || Blob.class.isAssignableFrom(type)) {
            return "BYTEA";
        } else if (type.equals(Duration.class) || type.equals(Period.class) || Interval.class.isAssignableFrom(type)) {
            return "INTERVAL";
        } else if (type.isEnum()) {
            return "VARCHAR(255)";
        } else if (type.equals(UUID.class)) {
            return "UUID";
        } else if (type.equals(String.class)) {
            return property.isIdProperty() ? "VARCHAR(36)" : "VARCHAR(255)";
        } else if (type.equals(Long.class) || type.equals(long.class) || type.equals(BigInteger.class)) {
            return "BIGINT";
        } else if (type.equals(BigDecimal.class)
                || type.equals(Double.class) || type.equals(double.class)
                || type.equals(Float.class) || type.equals(float.class)) {
            if (field != null && field.isAnnotationPresent(NumericPrecisionScale.class)) {
                NumericPrecisionScale numericPrecisionScale = field.getAnnotation(NumericPrecisionScale.class);
                return String.format("NUMERIC(%d,%d)", numericPrecisionScale.precision(),
                        numericPrecisionScale.scale());
            }
            return "NUMERIC";
        } else if (type.equals(Integer.class) || type.equals(int.class)) {
            return "INT";
        } else if (type.equals(Boolean.class) || type.equals(boolean.class)) {
            return "BOOLEAN";
        } else if (type.equals(Instant.class) || type.equals(OffsetDateTime.class)
                || type.equals(ZonedDateTime.class)) {
            return "TIMESTAMPTZ";
        } else if (type.equals(LocalDateTime.class)) {
            return "TIMESTAMP";
        } else if (type.equals(LocalDate.class)) {
            return "DATE";
        } else if (type.equals(LocalTime.class)) {
            return "TIME";
        } else if (type.equals(OffsetTime.class)) {
            return "TIMETZ";
        }
        return "VARCHAR(255)";
    }
}