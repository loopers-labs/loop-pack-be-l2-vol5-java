package com.loopers.application.like.fixture;

import com.loopers.domain.like.ProductLike;
import com.loopers.domain.like.ProductLikeRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(readOnly = true)
public class LikeFixture {
    @Autowired private ProductLikeRepository likes;
    @PersistenceContext private EntityManager entityManager;

    @Transactional
    public void createLike(long userId, long productId) {
        likes.save(new ProductLike(userId, productId));
    }

    public long rowCount() {
        return entityManager
                .createQuery("select count(e) from ProductLikeJpaEntity e", Long.class)
                .getSingleResult();
    }
}
