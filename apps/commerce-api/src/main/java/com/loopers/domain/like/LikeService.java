package com.loopers.domain.like;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class LikeService {

    private final LikeRepository likeRepository;

    @Transactional(readOnly = true)
    public long countByProduct(Long productId) {
        return likeRepository.countByProductId(productId);
    }

    @Transactional(readOnly = true)
    public Map<Long, Long> countByProducts(List<Long> productIds) {
        return likeRepository.countByProductIds(productIds);
    }

    @Transactional(readOnly = true)
    public List<LikeModel> getUserLikes(Long userId) {
        return likeRepository.findAllByUserId(userId);
    }

    @Transactional
    public void like(Long userId, Long productId) {
        if (likeRepository.findByUserIdAndProductId(userId, productId).isEmpty()) {
            likeRepository.save(new LikeModel(userId, productId));
        }
    }

    @Transactional
    public void unlike(Long userId, Long productId) {
        likeRepository.findByUserIdAndProductId(userId, productId)
            .ifPresent(likeRepository::delete);
    }
}
