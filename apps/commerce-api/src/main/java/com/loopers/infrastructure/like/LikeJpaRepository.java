package com.loopers.infrastructure.like;

import com.loopers.domain.like.LikeModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LikeJpaRepository extends JpaRepository<LikeModel, Long> {
    Optional<LikeModel> findByUserIdAndProductId(Long userId, Long productId);

    Page<LikeModel> findByUserIdAndDeletedAtIsNull(Long userId, Pageable pageable);

    long countByProductIdAndDeletedAtIsNull(Long productId);

    List<LikeModel> findByProductIdInAndDeletedAtIsNull(List<Long> productIds);
}
