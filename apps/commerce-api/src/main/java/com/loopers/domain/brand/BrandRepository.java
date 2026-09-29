package com.loopers.domain.brand;

import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageSize;
import com.loopers.domain.common.PageWindow;

import java.util.Optional;

public interface BrandRepository {

    Brand save(Brand brand);

    Optional<Brand> findById(Long id);

    Optional<Brand> findByIdForShare(Long id);

    Optional<Brand> findByIdForUpdate(Long id);

    PageWindow<Brand> findPage(PageNumber page, PageSize size);
}
