package com.loopers.infrastructure.like;

import com.loopers.domain.like.LikeModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LikeJpaRepository extends JpaRepository<LikeModel, Long> {

    long countByProductId(Long productId);

    Optional<LikeModel> findByUserIdAndProductId(Long userId, Long productId);

    List<LikeModel> findAllByUserId(Long userId);

    @Query("""
        select l.productId as productId, count(l.id) as likeCount
        from LikeModel l
        where l.productId in :productIds
        group by l.productId
        """)
    List<LikeCountProjection> countGroupByProductIds(@Param("productIds") List<Long> productIds);

    interface LikeCountProjection {
        Long getProductId();

        Long getLikeCount();
    }
}
