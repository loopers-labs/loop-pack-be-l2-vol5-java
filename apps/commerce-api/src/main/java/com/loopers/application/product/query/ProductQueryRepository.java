package com.loopers.application.product.query;

import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** 상품 조회 전용 Repository. 같은 BC 안 AG 를 조인해 {@link ProductView} 로 바로 반환한다. BC 간 조인은 하지 않는다 (DR-31). */
public interface ProductQueryRepository {
    /** FR-PRODUCT-01: 삭제되지 않은 상품만, brandId 가 있으면 그 브랜드만 (DR-35), 정렬 하나 (ASM-08). */
    PageResult<ProductView.Summary> findActivePage(Long brandId, ProductSort sort, PageQuery query);

    /** FR-PRODUCT-02: 존재하고 삭제되지 않은 상품. */
    Optional<ProductView.Summary> findActive(Long productId);

    /** FR-PRODUCT-02 캐시 (DR-37): 존재하고 삭제되지 않은 상품의 상품·브랜드 revision 과 좋아요 수. 쿼리 하나로 읽는다. */
    Optional<ProductView.DetailState> findDetailState(Long productId);

    /** FR-PRODUCT-01 캐시 (DR-37): latest 첫 페이지 목록의 현재 revision. 행이 없으면 IllegalStateException. */
    long findListRevision();

    /** FR-PRODUCT-01 캐시 (DR-37): 캐시에 담지 않는 좋아요 수. 행이 없는 상품 ID 는 결과에 없다. */
    Map<Long, Long> findLikeCounts(Collection<Long> productIds);

    /** FR-ADMIN-PRODUCT-01: 삭제 포함, 최신순. */
    PageResult<ProductView.Admin> findPage(PageQuery query);

    /** FR-ADMIN-PRODUCT-03: 삭제 여부 무관. */
    Optional<ProductView.Admin> find(Long productId);
}
