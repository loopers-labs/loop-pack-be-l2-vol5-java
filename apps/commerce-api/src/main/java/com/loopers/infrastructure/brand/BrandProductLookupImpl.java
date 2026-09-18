package com.loopers.infrastructure.brand;

import com.loopers.application.brand.BrandProductLookup;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BrandProductLookupImpl implements BrandProductLookup {
    private final EntityManager entityManager;
    @Override
    public boolean hasActiveProducts(long brandId) {
        return entityManager.createQuery("select count(p) from ProductJpaEntity p where p.brandId=:brandId and p.deletedAt is null", Long.class)
            .setParameter("brandId", brandId).getSingleResult() > 0;
    }
}
