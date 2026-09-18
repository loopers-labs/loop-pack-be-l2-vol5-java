package com.loopers.domain.brand;

import com.loopers.domain.common.PageResult;

public interface BrandQueryRepository {
    PageResult<Brand> findPage(int page, int size);
}
