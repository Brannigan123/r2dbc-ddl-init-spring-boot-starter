package io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.upsert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.mapping.PersistentPropertyAccessor;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.data.relational.core.mapping.RelationalPersistentEntity;
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

@Component
public class R2dbcUpsertTemplate {

    private final R2dbcEntityTemplate entityTemplate;
    private final RelationalMappingContext mappingContext;
    private final Map<Class<?>, UpsertMetadata> metadataCache = new ConcurrentHashMap<>();

    public R2dbcUpsertTemplate(R2dbcEntityTemplate entityTemplate, RelationalMappingContext mappingContext) {
        this.entityTemplate = entityTemplate;
        this.mappingContext = mappingContext;
    }

    public <T> Mono<T> upsert(T entity) {
        Objects.requireNonNull(entity, "Entity must not be null");

        Class<?> entityClass = entity.getClass();
        UpsertMetadata metadata = metadataCache.computeIfAbsent(entityClass, this::resolveMetadata);

        RelationalPersistentEntity<?> persistentEntity = mappingContext.getRequiredPersistentEntity(entityClass);
        PersistentPropertyAccessor<?> propertyAccessor = persistentEntity.getPropertyAccessor(entity);

        DatabaseClient.GenericExecuteSpec executeSpec = entityTemplate.getDatabaseClient().sql(metadata.sql());

        for (ColumnProperty binding : metadata.allColumns()) {
            Object value = extractPropertyValue(propertyAccessor, binding.propertyChain());
            if (value != null) {
                executeSpec = executeSpec.bind(binding.paramName(), value);
            } else {
                executeSpec = executeSpec.bindNull(binding.paramName(), binding.dataType());
            }
        }

        return executeSpec.fetch().rowsUpdated().thenReturn(entity);
    }

    private UpsertMetadata resolveMetadata(Class<?> entityClass) {
        RelationalPersistentEntity<?> entity = mappingContext.getRequiredPersistentEntity(entityClass);
        String tableName = entity.getTableName().getReference();

        List<ColumnProperty> allColumns = new ArrayList<>();
        collectColumnProperties(entity, false, new ArrayList<>(), allColumns);

        List<String> pkColumns = allColumns.stream()
                .filter(ColumnProperty::isPrimaryKey)
                .map(ColumnProperty::columnName)
                .toList();

        List<ColumnProperty> updateColumns = allColumns.stream()
                .filter(col -> !col.isPrimaryKey())
                .filter(col -> !col.isCreatedAudit())
                .toList();

        StringBuilder sql = new StringBuilder("INSERT INTO ")
                .append(quote(tableName))
                .append(" (")
                .append(allColumns.stream().map(c -> quote(c.columnName())).collect(Collectors.joining(", ")))
                .append(") VALUES (")
                .append(allColumns.stream().map(c -> ":" + c.paramName()).collect(Collectors.joining(", ")))
                .append(") ON CONFLICT (")
                .append(pkColumns.stream().map(this::quote).collect(Collectors.joining(", ")))
                .append(")");

        if (!updateColumns.isEmpty()) {
            sql.append(" DO UPDATE SET ")
                    .append(updateColumns.stream()
                            .map(c -> quote(c.columnName()) + " = EXCLUDED." + quote(c.columnName()))
                            .collect(Collectors.joining(", ")));
        } else {
            sql.append(" DO NOTHING");
        }

        return new UpsertMetadata(sql.toString(), allColumns);
    }

    private void collectColumnProperties(
            RelationalPersistentEntity<?> entity,
            boolean parentIsId,
            List<RelationalPersistentProperty> parentChain,
            List<ColumnProperty> columns) {

        for (RelationalPersistentProperty property : entity) {
            if (property.isTransient()) {
                continue;
            }

            boolean isId = property.isIdProperty() || parentIsId;
            List<RelationalPersistentProperty> currentChain = new ArrayList<>(parentChain);
            currentChain.add(property);

            if (property.isEmbedded()) {
                RelationalPersistentEntity<?> embeddedEntity = mappingContext.getPersistentEntity(
                        property.getTypeInformation().getActualType());
                if (embeddedEntity != null) {
                    collectColumnProperties(embeddedEntity, isId, currentChain, columns);
                }
            } else {
                String columnName = property.getColumnName().getReference();
                String paramName = currentChain.stream()
                        .map(RelationalPersistentProperty::getName)
                        .collect(Collectors.joining("_"));

                boolean isCreatedAudit = property.isAnnotationPresent(CreatedDate.class)
                        || property.isAnnotationPresent(CreatedBy.class);

                columns.add(new ColumnProperty(
                        columnName,
                        paramName,
                        property.getType(),
                        isId,
                        isCreatedAudit,
                        currentChain));
            }
        }
    }

    private Object extractPropertyValue(PersistentPropertyAccessor<?> accessor,
            List<RelationalPersistentProperty> chain) {
        Object current = accessor.getBean();
        for (RelationalPersistentProperty prop : chain) {
            if (current == null) {
                return null;
            }
            RelationalPersistentEntity<?> entity = mappingContext.getPersistentEntity(current.getClass());
            if (entity == null) {
                return null;
            }
            current = entity.getPropertyAccessor(current).getProperty(prop);
        }
        return current;
    }

    private String quote(String name) {
        return "\"" + name + "\"";
    }

    private record UpsertMetadata(String sql, List<ColumnProperty> allColumns) {
    }

    private record ColumnProperty(
            String columnName,
            String paramName,
            Class<?> dataType,
            boolean isPrimaryKey,
            boolean isCreatedAudit,
            List<RelationalPersistentProperty> propertyChain) {
    }
}