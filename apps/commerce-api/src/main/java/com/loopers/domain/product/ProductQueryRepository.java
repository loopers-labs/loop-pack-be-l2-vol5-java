package com.loopers.domain.product;

import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;

import java.util.Optional;

public interface ProductQueryRepository {
    /** 삭제되지 않은 상품의 상세를 브랜드명·좋아요 수·현재 재고와 함께 반환한다. */
    Optional<ProductQueryResult> findDetail(Long productId);

    /**
     * 상품과 브랜드가 모두 삭제되지 않았을 때만 브랜드 식별자와 DB 좋아요 수를 반환한다.
     * 기본정보 캐시 hit 에서도 노출 여부를 DB 로 확인하기 위해 사용하며 기본정보 열은 읽지 않는다.
     */
    Optional<ProductDetailData.DbResult> findVisibleWithLikeCount(Long productId);

    /** brandId 가 null 이면 전체 활성 상품을 조회한다. */
    PageResult<ProductQueryResult> findPage(Long brandId, PageCommand page, ProductSort sort);

    /** 관리자 목록: 활성 상품 전체를 Product 생성 순서로 조회한다. */
    PageResult<ProductQueryResult> findAllPage(PageCommand page, ListSort sort);

    /** 요청자가 좋아요한 활성 상품을 Like 생성 순서로 조회한다. */
    PageResult<ProductQueryResult> findLikedPage(Long userId, PageCommand page, ListSort sort);
}
