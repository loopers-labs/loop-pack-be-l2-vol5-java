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

    @Transactional
    public boolean register(Long userId, Long productId) {
        return likeRepository.registerIfAbsent(new LikeModel(userId, productId));
    }

    @Transactional
    public void cancel(Long userId, Long productId) {
        likeRepository.deleteRelationship(userId, productId);
    }

    @Transactional(readOnly = true)
    public List<LikeModel> getUserLikes(Long userId) {
        return likeRepository.findByUserId(userId);
    }

    @Transactional(readOnly = true)
    public Map<Long, Long> countByProductIds(List<Long> productIds) {
        return likeRepository.countByProductIds(productIds);
    }
}
