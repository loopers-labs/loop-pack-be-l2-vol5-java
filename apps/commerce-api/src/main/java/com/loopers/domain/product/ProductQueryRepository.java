package com.loopers.domain.product;

import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;

import java.util.Optional;

public interface ProductQueryRepository {
    /** 삭제되지 않은 상품의 상세를 브랜드명·좋아요 수·현재 재고와 함께 반환한다. */
    Optional<ProductQueryResult> findDetail(Long productId);

    /** brandId 가 null 이면 전체 활성 상품을 조회한다. */
    PageResult<ProductQueryResult> findPage(Long brandId, PageCommand page, ProductSort sort);

    /** 관리자 목록: 활성 상품 전체를 Product 생성 순서로 조회한다. */
    PageResult<ProductQueryResult> findAllPage(PageCommand page, ListSort sort);

    /** 요청자가 좋아요한 활성 상품을 Like 생성 순서로 조회한다. */
    PageResult<ProductQueryResult> findLikedPage(Long userId, PageCommand page, ListSort sort);
}
