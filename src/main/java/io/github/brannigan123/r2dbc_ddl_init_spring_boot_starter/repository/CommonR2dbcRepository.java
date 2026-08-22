package io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.repository;

import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@NoRepositoryBean
public interface CommonR2dbcRepository<T, ID> extends ReactiveCrudRepository<T, ID> {
    Mono<T> upsert(T entity);

    Flux<T> upsertAll(Iterable<T> entities);
}