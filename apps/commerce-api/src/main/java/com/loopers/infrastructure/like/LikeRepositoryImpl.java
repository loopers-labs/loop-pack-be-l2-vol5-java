package com.loopers.infrastructure.like;

import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeRepository;
import com.loopers.infrastructure.support.MySqlConstraintErrors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class LikeRepositoryImpl implements LikeRepository {

    private final LikeJpaRepository likeJpaRepository;
    private final JdbcTemplate jdbcTemplate;

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
        return likeJpaRepository.countGroupByProductIdIn(distinctIds).stream()
            .collect(Collectors.toMap(LikeCountRow::productId, LikeCountRow::likeCount));
    }

    @Override
    public boolean insertIfAbsent(Like like) {
        // 같은 DataSource의 현재 트랜잭션에 참여한다. JPA 저장 실패로 rollback-only가 되는 경로를 피한다.
        try {
            return jdbcTemplate.update("""
                INSERT INTO likes (user_id, product_id, created_at, updated_at)
                VALUES (?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, like.getUserId(), like.getProductId()) == 1;
        } catch (DuplicateKeyException exception) {
            if (MySqlConstraintErrors.isDuplicateKey(exception, "uk_like_user_product")) {
                return false;
            }
            throw exception;
        }
    }

    @Override
    public void delete(Like like) {
        likeJpaRepository.findByUserIdAndProductId(like.getUserId(), like.getProductId())
            .ifPresent(likeJpaRepository::delete);
    }
}
