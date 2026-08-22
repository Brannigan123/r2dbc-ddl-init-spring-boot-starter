package io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.repository;

import org.springframework.data.r2dbc.convert.R2dbcConverter;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.SimpleR2dbcRepository;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.data.relational.repository.query.RelationalEntityInformation;

import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.upsert.R2dbcUpsertTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class CommonR2dbcRepositoryImpl<T, ID> extends SimpleR2dbcRepository<T, ID>
        implements CommonR2dbcRepository<T, ID> {

    private final R2dbcUpsertTemplate upsertTemplate;

    public CommonR2dbcRepositoryImpl(
            RelationalEntityInformation<T, ID> entity,
            R2dbcEntityTemplate entityTemplate,
            R2dbcConverter converter) {
        super(entity, entityTemplate, converter);
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
}