package com.loopers.infrastructure.like;

import com.loopers.domain.like.LikeModel;
import com.loopers.domain.like.LikeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class LikeRepositoryImpl implements LikeRepository {

    private final LikeJpaRepository likeJpaRepository;
    private final JdbcTemplate jdbc;

    @Override
    public boolean registerIfAbsent(LikeModel like) {
        try {
            jdbc.update("insert into product_like (user_id, product_id, created_at, updated_at) "
                + "values (?, ?, utc_timestamp(6), utc_timestamp(6))", like.getUserId(), like.getProductId());
            return true;
        } catch (DuplicateKeyException exception) {
            // Only the expected relationship conflict is an idempotent success.
            // JDBC statement failure does not mark the surrounding JPA transaction rollback-only.
            String message = exception.getMostSpecificCause().getMessage();
            if (message == null || !message.contains("uk_product_like_user_product")) {
                throw exception;
            }
            return false;
        }
    }

    @Override
    public void deleteRelationship(Long userId, Long productId) {
        jdbc.update("delete from product_like where user_id = ? and product_id = ?", userId, productId);
    }

    @Override
    public Optional<LikeModel> find(Long userId, Long productId) {
        return likeJpaRepository.findByUserIdAndProductId(userId, productId);
    }

    @Override
    public LikeModel save(LikeModel like) {
        return likeJpaRepository.save(like);
    }

    @Override
    public void delete(LikeModel like) {
        likeJpaRepository.delete(like);
    }

    @Override
    public List<LikeModel> findByUserId(Long userId) {
        return likeJpaRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Override
    public Map<Long, Long> countByProductIds(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return likeJpaRepository.countByProductIds(productIds).stream()
            .collect(Collectors.toMap(
                row -> (Long) row[0],
                row -> ((Number) row[1]).longValue()
            ));
    }
}
