package io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.repository;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.convert.R2dbcConverter;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.SimpleR2dbcRepository;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.repository.query.RelationalEntityInformation;

import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.upsert.R2dbcUpsertTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class CommonR2dbcRepositoryImpl<T, ID> extends SimpleR2dbcRepository<T, ID>
        implements CommonR2dbcRepository<T, ID> {

    private final R2dbcEntityTemplate entityTemplate;
    private final RelationalEntityInformation<T, ID> entityInformation;
    private final R2dbcUpsertTemplate upsertTemplate;

    public CommonR2dbcRepositoryImpl(
            RelationalEntityInformation<T, ID> entity,
            R2dbcEntityTemplate entityTemplate,
            R2dbcConverter converter) {
        super(entity, entityTemplate, converter);
        this.entityInformation = entity;
        this.entityTemplate = entityTemplate;
        RelationalMappingContext mappingContext = (RelationalMappingContext) converter.getMappingContext();
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
}