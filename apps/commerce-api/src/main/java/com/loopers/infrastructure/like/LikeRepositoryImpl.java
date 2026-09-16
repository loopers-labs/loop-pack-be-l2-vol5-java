package com.loopers.infrastructure.like;

import com.loopers.domain.like.LikeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class LikeRepositoryImpl implements LikeRepository {

    private final LikeJpaRepository likeJpaRepository;

    @Override
    public long countByProductId(Long productId) {
        return likeJpaRepository.countByProductId(productId);
    }

    @Override
    public Map<Long, Long> countByProductIds(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return likeJpaRepository.countGroupByProductIds(productIds).stream()
            .collect(Collectors.toMap(
                LikeJpaRepository.LikeCountProjection::getProductId,
                LikeJpaRepository.LikeCountProjection::getLikeCount
            ));
    }
}
