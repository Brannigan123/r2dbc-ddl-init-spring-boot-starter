package io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.repository;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.convert.R2dbcConverter;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.core.StatementMapper;
import org.springframework.data.r2dbc.repository.support.SimpleR2dbcRepository;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.data.relational.core.mapping.RelationalPersistentEntity;
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.repository.query.RelationalEntityInformation;
import org.springframework.r2dbc.core.DatabaseClient;

import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.annotation.SearchIndex;
import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.upsert.R2dbcUpsertTemplate;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class CommonR2dbcRepositoryImpl<T, ID> extends SimpleR2dbcRepository<T, ID>
        implements CommonR2dbcRepository<T, ID> {

    private final R2dbcEntityTemplate entityTemplate;
    private final RelationalEntityInformation<T, ID> entityInformation;
    private final R2dbcUpsertTemplate upsertTemplate;
    private final RelationalMappingContext mappingContext;
    private final R2dbcConverter converter;

    public CommonR2dbcRepositoryImpl(
            RelationalEntityInformation<T, ID> entity,
            R2dbcEntityTemplate entityTemplate,
            R2dbcConverter converter) {
        super(entity, entityTemplate, converter);
        this.entityInformation = entity;
        this.entityTemplate = entityTemplate;
        this.converter = converter;
        this.mappingContext = (RelationalMappingContext) converter.getMappingContext();
        this.upsertTemplate = new R2dbcUpsertTemplate(entityTemplate, mappingContext);
    }

    @Override
    public Mono<T> upsert(T entity) {
        return upsertTemplate.upsert(entity);
    }

    @Override
    public Flux<T> upsertAll(Iterable<T> entities) {
        return Flux.fromIterable(entities)
                .concatMap(upsertTemplate::upsert);
    }

    @Override
    public Mono<Page<T>> findAll(Pageable pageable) {
        return findAll(Criteria.empty(), pageable, Function.identity());
    }

    @Override
    public Mono<Page<T>> findAll(Criteria criteria, Pageable pageable) {
        return findAll(criteria, pageable, Function.identity());
    }

    @Override
    public <R> Mono<Page<R>> findAll(Criteria criteria, Pageable pageable, Function<T, R> mapper) {
        Criteria criteriaToUse = criteria != null ? criteria : Criteria.empty();
        Class<T> domainType = entityInformation.getJavaType();

        Query query = Query.query(criteriaToUse);
        if (pageable.isPaged()) {
            query = query.limit(pageable.getPageSize())
                    .offset(pageable.getOffset());
        }
        if (pageable.getSort().isSorted()) {
            query = query.sort(pageable.getSort());
        }

        Query countQuery = Query.query(criteriaToUse);

        Mono<List<R>> contentMono = entityTemplate.select(domainType)
                .matching(query)
                .all()
                .map(mapper)
                .collectList();

        Mono<Long> countMono = entityTemplate.select(domainType)
                .matching(countQuery)
                .count();

        return Mono.zip(contentMono, countMono)
                .map(tuple -> new PageImpl<>(tuple.getT1(), pageable, tuple.getT2()));
    }

    @Override
    public Mono<Long> delete(Criteria criteria) {
        Criteria criteriaToUse = criteria != null ? criteria : Criteria.empty();
        Class<T> domainType = entityInformation.getJavaType();

        Query query = Query.query(criteriaToUse);
        return entityTemplate.delete(domainType).matching(query).all();
    }

    @Override
    public Mono<Page<T>> findAllWithSearch(String searchQuery, Pageable pageable) {
        return findAllWithSearch(searchQuery, Criteria.empty(), pageable, Function.identity());
    }

    @Override
    public Mono<Page<T>> findAllWithSearch(String searchQuery, Criteria criteria, Pageable pageable) {
        return findAllWithSearch(searchQuery, criteria, pageable, Function.identity());
    }

    @Override
    @SuppressWarnings("java:S1192")
    public <R> Mono<Page<R>> findAllWithSearch(String searchQuery, Criteria criteria, Pageable pageable, Function<T, R> mapper) {
        if (searchQuery == null || searchQuery.trim().isEmpty()) {
            return findAll(criteria, pageable, mapper);
        }

        Class<T> domainType = entityInformation.getJavaType();
        SearchIndexMetadata searchMeta = resolveSearchIndexMetadata(domainType);
        String tableName = entityInformation.getTableName().getReference();

        String whereClause = searchMeta.columnName() + " @@ websearch_to_tsquery('" + searchMeta.config() + "', :searchQuery)";

        Criteria criteriaToUse = criteria != null ? criteria : Criteria.empty();
        if (!criteriaToUse.isEmpty()) {
            StatementMapper statementMapper = entityTemplate.getDataAccessStrategy().getStatementMapper();
            StatementMapper.SelectSpec selectSpec = statementMapper.createSelect(tableName)
                    .withCriteria(criteriaToUse);

            String renderedSql = statementMapper.getMappedObject(selectSpec).toQuery();
            int whereIndex = renderedSql.indexOf(" WHERE ");
            if (whereIndex != -1) {
                String criteriaSql = renderedSql.substring(whereIndex + 7);
                whereClause += " AND (" + criteriaSql + ")";
            }
        }

        String selectSql = "SELECT * FROM " + tableName + " WHERE " + whereClause + buildSortClause(pageable.getSort());
        if (pageable.isPaged()) {
            selectSql += " LIMIT " + pageable.getPageSize() + " OFFSET " + pageable.getOffset();
        }

        String countSql = "SELECT COUNT(*) FROM " + tableName + " WHERE " + whereClause;

        DatabaseClient client = entityTemplate.getDatabaseClient();
        BiFunction<Row, RowMetadata, T> rowMapper = (row, metadata) -> converter.read(domainType, row, metadata);

        Mono<List<R>> contentMono = client.sql(selectSql)
                .bind("searchQuery", searchQuery)
                .map(rowMapper)
                .all()
                .map(mapper)
                .collectList();

        Mono<Long> countMono = client.sql(countSql)
                .bind("searchQuery", searchQuery)
                .map((row, metadata) -> Objects.requireNonNull(row.get(0, Long.class)))
                .first()
                .defaultIfEmpty(0L);

        return Mono.zip(contentMono, countMono)
                .map(tuple -> new PageImpl<>(tuple.getT1(), pageable, tuple.getT2()));
    }

    private SearchIndexMetadata resolveSearchIndexMetadata(Class<T> domainType) {
        RelationalPersistentEntity<?> entity = mappingContext.getRequiredPersistentEntity(domainType);
        for (RelationalPersistentProperty property : entity) {
            Field field = property.getField();
            if (field != null && field.isAnnotationPresent(SearchIndex.class)) {
                SearchIndex annotation = field.getAnnotation(SearchIndex.class);
                String columnName = property.getColumnName().getReference();
                return new SearchIndexMetadata(columnName, annotation.config());
            }
        }
        throw new IllegalStateException("No field annotated with @SearchIndex found on " + domainType.getName());
    }

    private String buildSortClause(Sort sort) {
        if (sort.isUnsorted()) {
            return "";
        }
        List<String> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            orders.add(order.getProperty() + " " + order.getDirection().name());
        }
        return " ORDER BY " + String.join(", ", orders);
    }

    private record SearchIndexMetadata(String columnName, String config) {}
}