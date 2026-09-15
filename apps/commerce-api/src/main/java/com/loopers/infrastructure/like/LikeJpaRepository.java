package com.loopers.infrastructure.like;

import com.loopers.domain.like.LikeModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;

public interface LikeJpaRepository extends JpaRepository<LikeModel, Long> {

    /**
     * 원자적 멱등 INSERT (ADR-13). KEY는 unique(user_id, product_id)이며, 이미 있으면 값을 바꾸지 않는 UPDATE가 실행된다.
     * 이 문법은 테이블의 모든 UNIQUE 인덱스에 반응하므로 likes에 UNIQUE 인덱스를 추가할 때 다시 본다.
     */
    @Modifying
    @Query(
        value = "INSERT INTO likes (user_id, product_id, created_at) VALUES (:userId, :productId, :likedAt) "
            + "ON DUPLICATE KEY UPDATE user_id = user_id",
        nativeQuery = true
    )
    int insertIgnoringDuplicate(
        @Param("userId") Long userId,
        @Param("productId") Long productId,
        @Param("likedAt") ZonedDateTime likedAt
    );

    @Modifying
    @Query("DELETE FROM LikeModel l WHERE l.userId = :userId AND l.productId = :productId")
    int deleteByUserIdAndProductId(@Param("userId") Long userId, @Param("productId") Long productId);

    List<LikeModel> findAllByUserIdOrderByIdDesc(Long userId);
}
