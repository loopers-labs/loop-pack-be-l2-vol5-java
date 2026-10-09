package com.loopers.infrastructure.productlike;

import com.loopers.domain.productlike.ProductLikeModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductLikeJpaRepository extends JpaRepository<ProductLikeModel, Long> {
    Optional<ProductLikeModel> findByUserIdAndProductId(Long userId, Long productId);
}
