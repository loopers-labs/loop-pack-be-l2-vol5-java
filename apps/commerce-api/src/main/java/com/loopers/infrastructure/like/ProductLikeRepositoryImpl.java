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
        return entityManager
                        .createQuery(
                                "select count(l) from ProductLikeJpaEntity l where l.userId=:userId"
                                    + " and l.productId=:productId",
                                Long.class)
                        .setParameter("userId", userId)
                        .setParameter("productId", productId)
                        .getSingleResult()
                > 0;
    }

    @Override
    public void save(ProductLike like) {
        entityManager.persist(new ProductLikeJpaEntity(like));
    }

    @Override
    public void registerIfAbsent(ProductLike like) {
        entityManager
                .createNativeQuery(
                        "insert into product_likes (user_id, product_id, created_at, updated_at) "
                                + "values (:userId, :productId, CURRENT_TIMESTAMP(6), "
                                + "CURRENT_TIMESTAMP(6)) on duplicate key update user_id = user_id")
                .setParameter("userId", like.userId())
                .setParameter("productId", like.productId())
                .executeUpdate();
    }

    @Override
    public void delete(long userId, long productId) {
        entityManager
                .createQuery(
                        "delete from ProductLikeJpaEntity l where l.userId=:userId and"
                            + " l.productId=:productId")
                .setParameter("userId", userId)
                .setParameter("productId", productId)
                .executeUpdate();
    }
}
