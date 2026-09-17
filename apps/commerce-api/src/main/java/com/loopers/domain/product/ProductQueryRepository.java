package com.loopers.domain.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

/**
 * 고객 상품 조회 전용 약속 (ADR-02, ISP).
 * 좋아요 수를 정렬과 표시에 같은 조회로 쓰도록 상품과 함께 돌려준다 (LIK-04).
 */
public interface ProductQueryRepository {

    /**
     * 팔 수 있는 상품 페이지. brandId가 null이면 전체. 모든 정렬의 동률 보조 기준은 id 내림차순 (P-09).
     */
    Page<ProductWithLikes> findSellable(Long brandId, ProductSort sort, Pageable pageable);

    Optional<ProductWithLikes> findSellableById(Long productId);
}
