package com.loopers.domain.brand;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BrandRepository {
    Brand save(Brand brand);

    Optional<Brand> findById(Long id);

    /**
     * 삭제된 브랜드도 포함해 식별자 목록으로 한 번에 조회한다.
     */
    List<Brand> findAllByIdIn(Collection<Long> ids);

    /**
     * 관리자 조회용. 삭제된 브랜드도 포함하며 최신순으로 반환한다.
     */
    List<Brand> findAllForAdmin(int page, int size);
}
