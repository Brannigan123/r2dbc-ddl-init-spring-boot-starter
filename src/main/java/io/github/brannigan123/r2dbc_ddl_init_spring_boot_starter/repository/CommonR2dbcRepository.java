package io.github.brannigan123.r2dbc_ddl_init_spring_boot_starter.repository;

import java.util.function.Function;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@NoRepositoryBean
public interface CommonR2dbcRepository<T, ID> extends ReactiveCrudRepository<T, ID> {
    Mono<T> upsert(T entity);

    Flux<T> upsertAll(Iterable<T> entities);

    Mono<Page<T>> findAll(Pageable pageable);

    Mono<Page<T>> findAll(Criteria criteria, Pageable pageable);

    <R> Mono<Page<R>> findAll(Criteria criteria, Pageable pageable, Function<T, R> mapper);

    Mono<Long> delete(Criteria criteria);
}