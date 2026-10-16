package com.loopers.infrastructure.like;

import com.loopers.domain.like.LikeModel;
import com.loopers.domain.like.LikeCountModel;
import com.loopers.domain.like.LikeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

@RequiredArgsConstructor
@Component
@Transactional
public class LikeRepositoryImpl implements LikeRepository {

    private final LikeJpaRepository likeJpaRepository;
    private final JdbcTemplate jdbc;

    @Override
    public void initializeCount(LikeCountModel count) {
        jdbc.update("insert into product_like_count (product_id, like_count) values (?, ?)",
            count.getProductId(), count.getLikeCount());
    }

    @Override
    public boolean registerIfAbsent(LikeModel like) {
        try {
            jdbc.update("insert into product_like (user_id, product_id, created_at, updated_at) "
                + "values (?, ?, utc_timestamp(6), utc_timestamp(6))", like.getUserId(), like.getProductId());
        } catch (DuplicateKeyException exception) {
            // Only the expected relationship conflict is an idempotent success.
            // JDBC statement failure does not mark the surrounding JPA transaction rollback-only.
            String message = exception.getMostSpecificCause().getMessage();
            if (message == null || !message.contains("uk_product_like_user_product")) {
                throw exception;
            }
            return false;
        }
        adjustCount(like.getProductId(), 1);
        return true;
    }

    @Override
    public boolean deleteRelationship(Long userId, Long productId) {
        int deleted = jdbc.update("delete from product_like where user_id = ? and product_id = ?", userId, productId);
        if (deleted != 0) {
            adjustCount(productId, -deleted);
        }
        return deleted != 0;
    }

    @Override
    public Optional<LikeModel> find(Long userId, Long productId) {
        return likeJpaRepository.findByUserIdAndProductId(userId, productId);
    }

    @Override
    public LikeModel save(LikeModel like) {
        registerIfAbsent(like);
        return likeJpaRepository.findByUserIdAndProductId(like.getUserId(), like.getProductId()).orElseThrow();
    }

    @Override
    public void delete(LikeModel like) {
        deleteRelationship(like.getUserId(), like.getProductId());
    }

    @Override
    public List<LikeModel> findByUserId(Long userId) {
        return likeJpaRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Long> countByProductIds(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        var ids = new HashSet<>(productIds);
        Map<Long, Long> counts = new HashMap<>();
        new NamedParameterJdbcTemplate(jdbc).query(
            "select product_id, like_count from product_like_count where product_id in (:ids)",
            Map.of("ids", ids), (org.springframework.jdbc.core.RowCallbackHandler) row ->
                counts.put(row.getLong("product_id"), row.getLong("like_count")));
        if (counts.size() != ids.size()) {
            throw new IllegalStateException("상품 좋아요 카운터가 누락되었습니다. 초기화 상태를 확인하세요.");
        }
        return counts;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> findLikedProductIds(Long userId, List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Set.of();
        }
        var ids = new NamedParameterJdbcTemplate(jdbc).queryForList(
            "select product_id from product_like where user_id = :userId and product_id in (:ids)",
            Map.of("userId", userId, "ids", new HashSet<>(productIds)), Long.class);
        return Set.copyOf(ids);
    }

    private void adjustCount(Long productId, int delta) {
        int updated = jdbc.update("update product_like_count set like_count = like_count + ? "
            + "where product_id = ? and like_count + ? >= 0", delta, productId, delta);
        if (updated != 1) {
            throw new IllegalStateException("상품 좋아요 카운터가 누락되었거나 일치하지 않습니다.");
        }
    }
}
