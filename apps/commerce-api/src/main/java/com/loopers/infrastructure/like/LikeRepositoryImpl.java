package com.loopers.infrastructure.like;

import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class LikeRepositoryImpl implements LikeRepository {

    private final LikeJpaRepository likeJpaRepository;
    private final EntityManager entityManager;

    @Override
    public Optional<Like> findByUserIdAndProductId(Long userId, Long productId) {
        return likeJpaRepository.findByUserIdAndProductId(userId, productId).map(LikeJpaMapper::toDomain);
    }

    @Override
    public List<Like> findAllByUserId(Long userId) {
        return likeJpaRepository.findAllByUserId(userId).stream().map(LikeJpaMapper::toDomain).toList();
    }

    @Override
    public long countByProductId(Long productId) {
        return likeJpaRepository.countByProductId(productId);
    }

    @Override
    public Map<Long, Long> countByProductIds(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinctIds = productIds.stream().distinct().toList();
        Query query = entityManager.createNativeQuery(
            "SELECT l.product_id, COUNT(l.id) AS like_count FROM likes l"
                + " WHERE l.product_id IN (:productIds) GROUP BY l.product_id"
        );
        query.setParameter("productIds", distinctIds);
        Map<Long, Long> counts = new HashMap<>();
        List<?> rows = query.getResultList();
        for (Object result : rows) {
            Object[] row = (Object[]) result;
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return counts;
    }

    @Override
    public Like save(Like like) {
        LikeJpaEntity entity = likeJpaRepository.findByUserIdAndProductId(like.getUserId(), like.getProductId())
            .orElseGet(() -> LikeJpaMapper.toNewEntity(like));
        return LikeJpaMapper.toDomain(likeJpaRepository.save(entity));
    }

    @Override
    public void delete(Like like) {
        likeJpaRepository.findByUserIdAndProductId(like.getUserId(), like.getProductId())
            .ifPresent(likeJpaRepository::delete);
    }
}
