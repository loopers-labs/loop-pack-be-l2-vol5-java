package com.loopers.domain.catalog;

import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BrandRepository {
    BrandModel save(BrandModel brand);

    Optional<BrandModel> find(Long id);

    List<BrandModel> findByIds(Collection<Long> ids);

    /** 삭제 포함, 최신순 (FR-ADMIN-BRAND-01). */
    PageResult<BrandModel> findPage(PageQuery query);
}
