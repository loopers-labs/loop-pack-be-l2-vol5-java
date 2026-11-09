package com.loopers.infrastructure.like;

import com.loopers.domain.like.LikeModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LikeJpaRepository extends JpaRepository<LikeModel, Long> {
    Optional<LikeModel> findByUserIdAndProductId(Long userId, Long productId);

    Page<LikeModel> findByUserIdAndDeletedAtIsNull(Long userId, Pageable pageable);

    long countByProductIdAndDeletedAtIsNull(Long productId);

    // 좋아요 행을 가져오지 않고 DB에서 상품별로 센다. 좋아요가 없는 상품은 결과에 없다
    @Query("select l.productId as productId, count(l) as likeCount from LikeModel l "
        + "where l.productId in :productIds and l.deletedAt is null group by l.productId")
    List<ProductLikeCount> countActiveGroupByProductId(@Param("productIds") List<Long> productIds);
}
