package com.loopers.application.mall.query;

import com.loopers.application.common.PageCriteria;
import com.loopers.application.common.PageResult;
import java.util.Optional;

// 브랜드 조회 전용 DAO
public interface BrandQueryDao {
    Optional<BrandView> findById(long brandId);

    PageResult<BrandView> findAll(PageCriteria criteria);
}
