package com.loopers.infrastructure.like;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ProductLikeJpaRepository extends JpaRepository<ProductLikeEntity, Long> {

    @Modifying
    @Query(value = """
        INSERT IGNORE INTO product_like (user_id, product_id, created_at, updated_at)
        VALUES (:userId, :productId, NOW(6), NOW(6))
        """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId, @Param("productId") Long productId);

    @Modifying
    @Query(value = "DELETE FROM product_like WHERE user_id = :userId AND product_id = :productId", nativeQuery = true)
    int deleteRelation(@Param("userId") Long userId, @Param("productId") Long productId);
}
