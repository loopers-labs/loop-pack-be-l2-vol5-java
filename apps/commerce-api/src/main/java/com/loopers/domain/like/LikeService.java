package com.loopers.domain.like;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
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
    public void register(Long userId, Long productId) {
        if (likeRepository.existsByUserIdAndProductId(userId, productId)) {
            throw new CoreException(ErrorType.CONFLICT, "이미 좋아요한 상품입니다.");
        }
        likeRepository.save(new LikeModel(userId, productId));
    }

    @Transactional
    public void cancel(Long userId, Long productId) {
        likeRepository.deleteByUserIdAndProductId(userId, productId);
    }

    @Transactional(readOnly = true)
    public List<Long> getMyLikedProductIds(Long requesterId, Long userId) {
        if (!requesterId.equals(userId)) {
            throw new CoreException(ErrorType.NOT_FOUND);
        }
        return likeRepository.findProductIdsByUserId(userId);
    }

    @Transactional(readOnly = true)
    public long getLikeCount(Long productId) {
        return likeRepository.countByProductId(productId);
    }

    @Transactional(readOnly = true)
    public Map<Long, Long> getLikeCounts(List<Long> productIds) {
        return likeRepository.countByProductIds(productIds);
    }
}
