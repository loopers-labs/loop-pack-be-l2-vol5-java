package com.loopers.domain.like;

import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class LikeService {

    private final LikeRepository likeRepository;

    @Transactional(readOnly = true)
    public long countLikes(Long productId) {
        return likeRepository.countByProductId(productId);
    }

    @Transactional(readOnly = true)
    public Map<Long, Long> countLikes(Collection<Long> productIds) {
        return likeRepository.countByProductIds(productIds);
    }

    @Transactional
    public void like(Long userId, Long productId) {
        if (!likeRepository.exists(userId, productId)) {
            likeRepository.save(new Like(userId, productId));
        }
    }

    @Transactional
    public void unlike(Long userId, Long productId) {
        Like like = likeRepository.find(userId, productId)
            .orElseThrow(() -> new DomainException(DomainErrorType.NOT_FOUND, "[productId = " + productId + "] 좋아요한 상품이 아닙니다."));
        likeRepository.delete(like);
    }
}
