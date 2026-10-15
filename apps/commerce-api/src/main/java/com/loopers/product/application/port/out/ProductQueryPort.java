package com.loopers.product.application.port.out;

import com.loopers.product.domain.ProductModel;
import com.loopers.product.domain.ProductSort;
import com.loopers.product.domain.ProductWithLikes;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 고객 상품 조회 전용 약속 (ADR-02, ISP).
 * 좋아요 수를 정렬과 표시에 같은 조회로 쓰도록 상품과 함께 돌려준다 (LIK-04).
 */
public interface ProductQueryPort {

    /**
     * 팔 수 있는 상품 페이지. brandId가 null이면 전체. 모든 정렬의 동률 보조 기준은 id 내림차순 (P-09).
     */
    Page<ProductWithLikes> findSellable(Long brandId, ProductSort sort, Pageable pageable);

    Optional<ProductWithLikes> findSellableById(Long productId);

    /**
     * 주어진 상품 중 지금 팔 수 있는(삭제되지 않은) 상품. W4 목록 캐시가 hit일 때 비노출과 가격·이름을 원본으로 확인한다 (PK 조회 1번).
     */
    List<ProductModel> findSellableByIds(Collection<Long> productIds);
}
