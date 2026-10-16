package com.loopers.infrastructure.productlike;

import com.loopers.domain.productlike.ProductLikeModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductLikeJpaRepository extends JpaRepository<ProductLikeModel, Long> {
    Optional<ProductLikeModel> findByUserIdAndProductId(Long userId, Long productId);

    @Modifying
    @Query("delete from ProductLikeModel l where l.userId = :userId and l.productId = :productId")
    int deleteByUserIdAndProductId(@Param("userId") Long userId, @Param("productId") Long productId);
}
