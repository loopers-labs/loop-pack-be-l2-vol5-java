package com.loopers.domain.brand;

import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;

import java.util.Optional;

public interface BrandRepository {
    /** 삭제되지 않은 브랜드만 조회한다. */
    Optional<BrandModel> findActive(Long brandId);

    BrandModel save(BrandModel brand);

    PageResult<BrandModel> findActivePage(PageCommand page, ListSort sort);
}
