package com.loopers.infrastructure.like;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ProductLikeCountJpaRepository extends JpaRepository<ProductLikeCountEntity, Long> {

    @Modifying
    @Query(value = """
        INSERT INTO product_like_count (product_id, like_count) VALUES (:productId, 1)
        ON DUPLICATE KEY UPDATE like_count = like_count + 1
        """, nativeQuery = true)
    int increase(@Param("productId") Long productId);

    @Modifying
    @Query(value = "UPDATE product_like_count SET like_count = like_count - 1 WHERE product_id = :productId",
        nativeQuery = true)
    int decrease(@Param("productId") Long productId);
}
