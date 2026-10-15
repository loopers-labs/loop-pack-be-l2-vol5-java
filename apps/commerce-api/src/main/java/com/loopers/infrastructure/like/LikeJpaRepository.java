package com.loopers.infrastructure.like;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

import java.util.Optional;

public interface LikeJpaRepository extends JpaRepository<LikeJpaEntity, Long> {

    Optional<LikeJpaEntity> findByUserIdAndProductId(Long userId, Long productId);

    List<LikeJpaEntity> findAllByUserId(Long userId);

    long countByProductId(Long productId);

    // 좋아요가 없는 상품은 결과 행이 없다.
    @Query("""
        SELECT new com.loopers.infrastructure.like.LikeCountRow(l.productId, COUNT(l.id))
        FROM LikeJpaEntity l
        WHERE l.productId IN :productIds
        GROUP BY l.productId
        """)
    List<LikeCountRow> countGroupByProductIdIn(@Param("productIds") List<Long> productIds);
}
