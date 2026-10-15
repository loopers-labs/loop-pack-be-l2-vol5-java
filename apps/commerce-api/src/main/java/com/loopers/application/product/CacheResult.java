package com.loopers.application.product;

/**
 * 캐시 조회 결과의 세 상태. 캐시 포트는 예외를 던지지 않고 실패를 이 값으로 알린다.
 * miss(없음)와 unavailable(Redis 실패)을 구분하는 이유: 조회 쪽은 unavailable을 받으면
 * 같은 요청의 남은 Redis 호출(MGET·채우기 SET)을 건너뛰고 DB로만 응답한다 (docs/week4/design.md 8번 섹션).
 */
public sealed interface CacheResult<T> {

    record Hit<T>(T value) implements CacheResult<T> {
    }

    record Miss<T>() implements CacheResult<T> {
    }

    record Unavailable<T>() implements CacheResult<T> {
    }

    static <T> CacheResult<T> hit(T value) {
        return new Hit<>(value);
    }

    static <T> CacheResult<T> miss() {
        return new Miss<>();
    }

    static <T> CacheResult<T> unavailable() {
        return new Unavailable<>();
    }

    default boolean isUnavailable() {
        return this instanceof Unavailable;
    }
}
