package com.loopers.infrastructure.support;

import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.EntityPathBase;
import com.querydsl.core.types.dsl.PathBuilder;
import org.springframework.data.domain.Sort;

/**
 * 호출자가 Pageable에 담아 넘긴 Sort를 QueryDSL 정렬로 옮긴다 (관리자 목록의 id desc 등).
 * 정렬이 없으면 빈 배열이라 쿼리의 순서를 건드리지 않는다 — 파생 쿼리가 Sort를 해석하던 동작을 그대로 옮긴 것이다.
 */
public final class QueryDslSort {

    private QueryDslSort() {}

    public static <T> OrderSpecifier<?>[] of(Sort sort, EntityPathBase<T> root) {
        PathBuilder<T> path = new PathBuilder<>(root.getType(), root.getMetadata());
        return sort.stream()
            .map(order -> toOrderSpecifier(order, path))
            .toArray(OrderSpecifier<?>[]::new);
    }

    /**
     * 정렬 기준은 이름으로만 오므로 어떤 타입이든 비교 가능한 경로로 읽는다.
     */
    private static OrderSpecifier<?> toOrderSpecifier(Sort.Order order, PathBuilder<?> path) {
        return new OrderSpecifier<>(
            order.isAscending() ? Order.ASC : Order.DESC,
            path.getComparable(order.getProperty(), Comparable.class)
        );
    }
}
