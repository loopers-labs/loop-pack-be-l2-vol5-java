package com.loopers.application.productlike.query;

import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;

/** 좋아요 조회 전용 Repository. 같은 BC 안 AG 를 조인해 {@link ProductLikeView} 로 바로 반환한다. BC 간 조인은 하지 않는다 (DR-31). */
public interface ProductLikeQueryRepository {
    /** FR-LIKE-03: 사용자의 관계 중 상품이 삭제되지 않은 것만, 등록 최신순. */
    PageResult<ProductLikeView.Item> findPageByUserId(Long userId, PageQuery query);
}
