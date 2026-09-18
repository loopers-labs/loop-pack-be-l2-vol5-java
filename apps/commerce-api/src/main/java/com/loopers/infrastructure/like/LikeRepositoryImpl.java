package com.loopers.infrastructure.like;

import com.loopers.domain.like.LikeModel;
import com.loopers.domain.like.LikeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class LikeRepositoryImpl implements LikeRepository {
    private final LikeJpaRepository likeJpaRepository;

    @Override
    public LikeModel save(LikeModel like) {
        return likeJpaRepository.save(like);
    }

    @Override
    public Optional<LikeModel> findByUserIdAndProductId(Long userId, Long productId) {
        return likeJpaRepository.findByUserIdAndProductId(userId, productId);
    }

    @Override
    public Page<LikeModel> findActiveByUserId(Long userId, Pageable pageable) {
        return likeJpaRepository.findByUserIdAndDeletedAtIsNull(userId, pageable);
    }

    @Override
    public long countActiveByProductId(Long productId) {
        return likeJpaRepository.countByProductIdAndDeletedAtIsNull(productId);
    }

    @Override
    public Map<Long, Long> countActiveByProductIds(List<Long> productIds) {
        return likeJpaRepository.findByProductIdInAndDeletedAtIsNull(productIds).stream()
            .collect(Collectors.groupingBy(LikeModel::getProductId, Collectors.counting()));
    }
}
