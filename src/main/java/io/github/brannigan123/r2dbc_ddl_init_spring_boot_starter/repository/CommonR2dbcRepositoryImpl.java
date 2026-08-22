package io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.repository;

import io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.upsert.R2dbcUpsertTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class CommonR2dbcRepositoryImpl<T, ID> implements CommonR2dbcRepository<T, ID> {

    private final R2dbcUpsertTemplate upsertTemplate;

    public CommonR2dbcRepositoryImpl(R2dbcUpsertTemplate upsertTemplate) {
        this.upsertTemplate = upsertTemplate;
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