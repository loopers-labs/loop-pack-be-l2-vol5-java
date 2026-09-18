package com.loopers.infrastructure.like;

import com.loopers.domain.like.ProductLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductLikeJpaRepository extends JpaRepository<ProductLike, Long> {

    boolean existsByUserIdAndProductId(long userId, long productId);

    long countByProductId(long productId);

    @Modifying
    @Query("delete from ProductLike productLike where productLike.user.id = :userId and productLike.product.id = :productId")
    void deleteByUserIdAndProductId(@Param("userId") long userId, @Param("productId") long productId);
}
