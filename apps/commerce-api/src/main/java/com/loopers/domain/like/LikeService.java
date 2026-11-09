package com.loopers.domain.like;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class LikeService {

    private final LikeRepository likeRepository;

    @Transactional
    public void like(Long userId, Long productId) {
        LikeModel like = likeRepository.findByUserIdAndProductId(userId, productId)
            .orElseGet(() -> new LikeModel(userId, productId));
        like.restore();
        likeRepository.save(like);
    }

    @Transactional
    public void unlike(Long userId, Long productId) {
        likeRepository.findByUserIdAndProductId(userId, productId)
            .ifPresent(LikeModel::delete);
    }

    @Transactional(readOnly = true)
    public Page<LikeModel> getLikesByUser(Long userId, Pageable pageable) {
        return likeRepository.findActiveByUserId(userId, pageable);
    }

    @Transactional(readOnly = true)
    public long countActiveByProduct(Long productId) {
        return likeRepository.countActiveByProductId(productId);
    }

    @Transactional(readOnly = true)
    public Map<Long, Long> countActiveByProducts(List<Long> productIds) {
        return likeRepository.countActiveByProductIds(productIds);
    }
}
