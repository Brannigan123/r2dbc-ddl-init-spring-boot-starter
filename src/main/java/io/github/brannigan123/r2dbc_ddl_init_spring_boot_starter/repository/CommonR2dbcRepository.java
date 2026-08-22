package io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface CommonR2dbcRepository<T, ID> {
    Mono<T> upsert(T entity);

    Flux<T> upsertAll(Iterable<T> entities);
}