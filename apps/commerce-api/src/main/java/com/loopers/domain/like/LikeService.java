package com.loopers.domain.like;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RequiredArgsConstructor
@Component
public class LikeService {

    private final LikeRepository likeRepository;

    @Transactional
    public void initializeCount(Long productId) {
        likeRepository.initializeCount(new LikeCountModel(productId));
    }

    @Transactional
    public boolean register(Long userId, Long productId) {
        return likeRepository.registerIfAbsent(new LikeModel(userId, productId));
    }

    @Transactional
    public boolean cancel(Long userId, Long productId) {
        return likeRepository.deleteRelationship(userId, productId);
    }

    @Transactional(readOnly = true)
    public List<LikeModel> getUserLikes(Long userId) {
        return likeRepository.findByUserId(userId);
    }

    @Transactional(readOnly = true)
    public Map<Long, Long> countByProductIds(List<Long> productIds) {
        return likeRepository.countByProductIds(productIds);
    }

    @Transactional(readOnly = true)
    public Set<Long> findLikedProductIds(Long userId, List<Long> productIds) {
        return likeRepository.findLikedProductIds(userId, productIds);
    }
}
