package com.loopers.application.brand.query;

import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;

import java.util.Optional;

/** 브랜드 조회 전용 Repository. {@link BrandView} 로 바로 반환한다 (DR-31). */
public interface BrandQueryRepository {
    /** FR-BRAND-01: 존재하고 삭제되지 않은 브랜드. */
    Optional<BrandView.Summary> findActive(Long brandId);

    /** FR-ADMIN-BRAND-01: 삭제 포함, 최신순. */
    PageResult<BrandView.Admin> findPage(PageQuery query);

    /** FR-ADMIN-BRAND-03: 삭제 여부 무관. */
    Optional<BrandView.Admin> find(Long brandId);
}
