package com.loopers.infrastructure.like;

import com.loopers.domain.like.ProductLike;
import com.loopers.domain.like.ProductLikeRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional
public class ProductLikeRepositoryImpl implements ProductLikeRepository {
    private final EntityManager entityManager;
    @Override
    @Transactional(readOnly = true)
    public boolean exists(long userId, long productId) {
        return entityManager.createQuery("select count(l) from ProductLikeJpaEntity l where l.userId=:userId and l.productId=:productId", Long.class)
            .setParameter("userId", userId).setParameter("productId", productId).getSingleResult() > 0;
    }
    @Override
    public void save(ProductLike like) { entityManager.persist(new ProductLikeJpaEntity(like)); }
    @Override
    public void delete(long userId, long productId) {
        entityManager.createQuery("delete from ProductLikeJpaEntity l where l.userId=:userId and l.productId=:productId")
            .setParameter("userId", userId).setParameter("productId", productId).executeUpdate();
    }
}
