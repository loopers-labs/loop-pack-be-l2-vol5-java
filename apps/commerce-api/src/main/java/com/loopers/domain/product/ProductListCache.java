package com.loopers.domain.product;

import org.springframework.data.domain.Page;

import java.util.function.Supplier;

/**
 * 고객 상품 목록 첫 페이지의 캐시 (4주차 docs/week4/cache-design.md).
 * 범위(브랜드 · 전체)마다 버전을 두고, 무효화는 버전을 올려 옛 키를 버린다.
 */
public interface ProductListCache {

    /**
     * 범위 버전을 먼저 읽고, 그 버전의 캐시가 있으면 돌려주고 없으면 loader 로 원본을 읽어 그 버전에 저장한다.
     * loader 는 버전을 읽은 뒤에 부르므로 원본 읽기 트랜잭션은 loader 안에서 시작해야 한다 (4.1).
     * 캐시 저장소 오류는 원본 결과로 대신하며, loader 의 오류는 그대로 나간다 (4.2).
     *
     * @param brandId null 이면 브랜드 필터 없는 전체 목록
     */
    Page<ProductView> getFirstPage(Long brandId, ProductSort sort, Supplier<Page<ProductView>> loader);

    /**
     * 그 브랜드의 목록과 전체 목록을 무효화한다. 진행 중인 트랜잭션이 있으면 commit 뒤에 하고, rollback 되면 하지 않는다.
     * 무효화 실패는 쓰기 결과에 영향을 주지 않고 나중에 다시 시도한다 (3.4).
     */
    void invalidate(Long brandId);
}
