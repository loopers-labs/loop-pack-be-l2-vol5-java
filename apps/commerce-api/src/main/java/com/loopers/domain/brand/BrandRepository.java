package com.loopers.domain.brand;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BrandRepository {

    BrandModel save(BrandModel brand);

    /**
     * BRD-03: 삭제되지 않은 브랜드만 찾는다.
     */
    Optional<BrandModel> findActiveById(Long id);

    Page<BrandModel> findActive(Pageable pageable);

    /**
     * 조회 조합용. 삭제 여부와 관계없이 식별자로 한 번에 찾는다.
     */
    List<BrandModel> findAllByIds(Collection<Long> ids);
}
