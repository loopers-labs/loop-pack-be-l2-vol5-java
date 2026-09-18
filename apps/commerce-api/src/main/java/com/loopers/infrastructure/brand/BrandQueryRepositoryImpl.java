package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandQueryRepository;
import com.loopers.domain.common.PageResult;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class BrandQueryRepositoryImpl implements BrandQueryRepository {
    private final EntityManager entityManager;

    public BrandQueryRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public PageResult<Brand> findPage(int page, int size) {
        long total = entityManager.createQuery("select count(b) from Brand b", Long.class).getSingleResult();
        long offset = (long) page * size;
        List<Brand> items = offset >= total ? List.of() : entityManager.createQuery(
                "select b from Brand b order by b.createdAt desc, b.id desc", Brand.class)
            .setFirstResult(Math.toIntExact(offset)).setMaxResults(size).getResultList();
        return PageResult.of(items, page, size, total);
    }
}
