package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductLikeCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.Optional;

public interface ProductLikeCountJpaRepository extends JpaRepository<ProductLikeCount, Long> {

    @Modifying
    @Query("update ProductLikeCount c set c.likeCount = c.likeCount + 1, c.updatedAt = :updatedAt where c.productId = :productId")
    int increase(@Param("productId") Long productId, @Param("updatedAt") ZonedDateTime updatedAt);

    @Modifying
    @Query("update ProductLikeCount c set c.likeCount = c.likeCount - 1, c.updatedAt = :updatedAt where c.productId = :productId")
    int decrease(@Param("productId") Long productId, @Param("updatedAt") ZonedDateTime updatedAt);

    @Query("select c.likeCount from ProductLikeCount c where c.productId = :productId")
    Optional<Long> findLikeCount(@Param("productId") Long productId);
}
