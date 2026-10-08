package com.loopers.infrastructure.product;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Optional;

@Repository
public class ProductRepositoryImpl implements ProductRepository {

    private final ProductJpaRepository repository;
    private final EntityManager entityManager;

    public ProductRepositoryImpl(ProductJpaRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    public Product save(Product product) {
        return repository.saveAndFlush(product);
    }

    @Override
    public Optional<Product> findById(long productId) {
        return repository.findById(productId);
    }

    @Override
    public Optional<Product> lockById(long productId) {
        return repository.lockById(productId);
    }

    @Override
    public Optional<Long> findBrandId(long productId) {
        return repository.findBrandId(productId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int softDeleteNonDeletedByBrandId(long brandId, ZonedDateTime deletedAt) {
        var ids = entityManager.createQuery(
            "select p.id from Product p where p.brand.id = :brandId and p.deletedAt is null order by p.id", Long.class)
            .setParameter("brandId", brandId)
            .setFlushMode(FlushModeType.COMMIT)
            .getResultList();
        for (long id : ids) {
            // UPDATE 실행 계획에 기대지 않고, 기본 키별 잠금 조회 순서로 P13을 지킨다.
            entityManager.createNativeQuery("SELECT id FROM product WHERE id = :id FOR UPDATE", Long.class)
                .setParameter("id", id)
                .setFlushMode(FlushModeType.COMMIT)
                .getSingleResult();
        }
        entityManager.flush();
        if (ids.isEmpty()) {
            return 0;
        }
        int changed = entityManager.createQuery(
            "update Product p set p.deletedAt = :deletedAt, p.updatedAt = :deletedAt "
                + "where p.id in :ids and p.deletedAt is null")
            .setParameter("deletedAt", deletedAt)
            .setParameter("ids", ids)
            .executeUpdate();
        var persistenceUnit = entityManager.getEntityManagerFactory().getPersistenceUnitUtil();
        for (long id : ids) {
            Product reference = entityManager.getReference(Product.class, id);
            if (persistenceUnit.isLoaded(reference)) {
                entityManager.refresh(reference);
            }
        }
        return changed;
    }

    @Override
    public boolean existsNonDeletedByBrandId(long brandId) {
        return repository.existsByBrandIdAndDeletedAtIsNull(brandId);
    }
}
