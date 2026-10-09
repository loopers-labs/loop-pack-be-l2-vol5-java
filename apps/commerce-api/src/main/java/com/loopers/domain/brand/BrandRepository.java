package com.loopers.domain.brand;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BrandRepository {
    BrandModel save(BrandModel brand);

    Optional<BrandModel> find(Long id);

    Optional<BrandModel> findForShare(Long id);

    Optional<BrandModel> findForUpdate(Long id);

    List<BrandModel> findActiveByIds(Collection<Long> ids);

    List<BrandModel> findAllActive();

    boolean existsActiveByNameIgnoreCase(String name);
}
