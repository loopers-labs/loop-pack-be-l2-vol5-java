package com.loopers.like.infrastructure;

import com.loopers.like.domain.Like;
import com.loopers.like.domain.LikeRepository;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class LikeRepositoryAdapter implements LikeRepository {

    private final LikeJpaRepository jpaRepository;
    private final EntityManager entityManager;

    public LikeRepositoryAdapter(LikeJpaRepository jpaRepository, EntityManager entityManager) {
        this.jpaRepository = jpaRepository;
        this.entityManager = entityManager;
    }

    @Override
    public Like save(Like like) {
        if (like.getId() == 0L) {
            entityManager.persist(like);
            return like;
        }
        return jpaRepository.save(like);
    }

    @Override
    public Optional<Like> findByUserIdAndProductId(Long userId, Long productId) {
        return jpaRepository.findByUserIdAndProductId(userId, productId);
    }

    @Override
    public List<Like> findAllByUserId(Long userId, int page, int size) {
        return jpaRepository.findActiveProductLikesByUserId(
            userId,
            PageRequest.of(page, size)
        );
    }

    @Override
    public long countAllByUserId(Long userId) {
        return jpaRepository.countActiveProductLikesByUserId(userId);
    }

    @Override
    public long countByProductId(Long productId) {
        return jpaRepository.countByProductId(productId);
    }

    @Override
    public void delete(Like like) {
        jpaRepository.delete(like);
    }
}
