package io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.upsert;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.domain.ReactiveAuditorAware;
import org.springframework.data.mapping.PersistentPropertyAccessor;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.data.relational.core.mapping.RelationalPersistentEntity;
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.config.ApplicationContextProvider;
import reactor.core.publisher.Mono;

@Component
public class R2dbcUpsertTemplate {

    private final R2dbcEntityTemplate entityTemplate;
    private final RelationalMappingContext mappingContext;
    private final ObjectProvider<ReactiveAuditorAware<String>> auditorAwareProvider;
    private final Map<Class<?>, UpsertMetadata> metadataCache = new ConcurrentHashMap<>();

    public R2dbcUpsertTemplate(R2dbcEntityTemplate entityTemplate, RelationalMappingContext mappingContext) {
        this(entityTemplate, mappingContext, (ObjectProvider<ReactiveAuditorAware<String>>) null);
    }

    @Autowired
    @SuppressWarnings("unchecked")
    public R2dbcUpsertTemplate(
            R2dbcEntityTemplate entityTemplate,
            RelationalMappingContext mappingContext,
            ObjectProvider<?> auditorAwareProvider) {
        this.entityTemplate = entityTemplate;
        this.mappingContext = mappingContext;
        this.auditorAwareProvider = (ObjectProvider<ReactiveAuditorAware<String>>) auditorAwareProvider;
    }

    public <T> Mono<T> upsert(T entity) {
        if (entity == null) {
            return Mono.empty();
        }

        return Mono.defer(() -> applyAuditing(entity)
                .flatMap(this::executeUpsert));
    }

    private <T> Mono<T> applyAuditing(T entity) {
        Class<?> entityClass = entity.getClass();
        RelationalPersistentEntity<?> persistentEntity = mappingContext.getRequiredPersistentEntity(entityClass);

        boolean hasCreatedBy = persistentEntity.getPersistentProperty(CreatedBy.class) != null;
        boolean hasLastModifiedBy = persistentEntity.getPersistentProperty(LastModifiedBy.class) != null;

        if (!hasCreatedBy && !hasLastModifiedBy) {
            return Mono.just(populateAuditProperties(entity, persistentEntity, null));
        }

        return getAuditor()
                .map(auditor -> populateAuditProperties(entity, persistentEntity, auditor.isBlank() ? null : auditor));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private Mono<String> getAuditor() {
        ReactiveAuditorAware<String> auditorAware = null;

        if (auditorAwareProvider != null) {
            auditorAware = auditorAwareProvider.getIfAvailable();
        }

        if (auditorAware == null) {
            ApplicationContext context = ApplicationContextProvider.getApplicationContext();
            if (context != null) {
                if (context.containsBean("auditorProvider")) {
                    auditorAware = (ReactiveAuditorAware<String>) context.getBean("auditorProvider");
                } else if (context.containsBean("auditorAware")) {
                    auditorAware = (ReactiveAuditorAware<String>) context.getBean("auditorAware");
                } else {
                    ObjectProvider<ReactiveAuditorAware> provider = context.getBeanProvider(ReactiveAuditorAware.class);
                    auditorAware = provider.getIfAvailable();
                }
            }
        }

        if (auditorAware != null) {
            return auditorAware.getCurrentAuditor()
                    .defaultIfEmpty("")
                    .onErrorResume(e -> {
                        return Mono.just("");
                    });
        }

        return Mono.just("");
    }

    private <T> T populateAuditProperties(T entity, RelationalPersistentEntity<?> persistentEntity, String auditor) {
        PersistentPropertyAccessor<T> accessor = (PersistentPropertyAccessor<T>) persistentEntity
                .getPropertyAccessor(entity);
        Instant now = Instant.now();

        for (RelationalPersistentProperty prop : persistentEntity) {
            if (prop.isAnnotationPresent(CreatedDate.class)) {
                Object currentVal = accessor.getProperty(prop);
                if (currentVal == null) {
                    accessor.setProperty(prop, convertTemporal(now, prop.getType()));
                }
            } else if (prop.isAnnotationPresent(LastModifiedDate.class)) {
                accessor.setProperty(prop, convertTemporal(now, prop.getType()));
            } else if (prop.isAnnotationPresent(CreatedBy.class)) {
                Object currentVal = accessor.getProperty(prop);
                if (currentVal == null && auditor != null) {
                    accessor.setProperty(prop, auditor);
                }
            } else if (prop.isAnnotationPresent(LastModifiedBy.class)) {
                if (auditor != null) {
                    accessor.setProperty(prop, auditor);
                }
            }
        }
        return accessor.getBean();
    }

    private Object convertTemporal(Instant instant, Class<?> targetType) {
        if (targetType.equals(Instant.class)) {
            return instant;
        } else if (targetType.equals(LocalDateTime.class)) {
            return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        } else if (targetType.equals(OffsetDateTime.class)) {
            return instant.atOffset(ZoneOffset.UTC);
        } else if (targetType.equals(ZonedDateTime.class)) {
            return instant.atZone(ZoneOffset.UTC);
        } else if (targetType.equals(Long.class) || targetType.equals(long.class)) {
            return instant.toEpochMilli();
        }
        return instant;
    }

    private <T> Mono<T> executeUpsert(T entity) {
        Class<?> entityClass = entity.getClass();
        RelationalPersistentEntity<?> persistentEntity = mappingContext.getRequiredPersistentEntity(entityClass);
        UpsertMetadata metadata = metadataCache.computeIfAbsent(entityClass, this::resolveMetadata);
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