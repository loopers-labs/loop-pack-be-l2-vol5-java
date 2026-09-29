package com.loopers.domain.like;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class LikeService {

    private final LikeRepository likeRepository;

    /**
     * 이미 등록된 관계면 저장하지 않고 멱등하게 처리한다.
     *
     * @return 새로 등록되었으면 true, 이미 있었으면 false
     */
    @Transactional
    public boolean like(Long userId, Long productId) {
        if (likeRepository.findByUserIdAndProductId(userId, productId).isPresent()) {
            return false;
        }
        likeRepository.save(new Like(userId, productId));
        return true;
    }

    /**
     * 본인의 관계만 취소할 수 있다.
     * 다른 사용자의 관계는 조회 자체가 되지 않아 존재를 알리지 않는다.
     */
    @Transactional
    public void unlike(Long userId, Long productId) {
        Like like = likeRepository.findByUserIdAndProductId(userId, productId)
            .orElseThrow(() -> new CoreException(
                ErrorType.NOT_FOUND, "[userId = " + userId + ", productId = " + productId + "] 좋아요를 찾을 수 없습니다."));

        likeRepository.delete(like);
    }

    @Transactional(readOnly = true)
    public List<Like> getLikes(Long userId) {
        return likeRepository.findAllByUserId(userId);
    }

    @Transactional(readOnly = true)
    public long countByProductId(Long productId) {
        return likeRepository.countByProductId(productId);
    }

    /**
     * 여러 상품의 좋아요 수를 한 번에 집계한다. 좋아요가 없는 상품은 0 으로 채운다.
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> countByProductIds(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Long> counts = likeRepository.countByProductIds(productIds);
        return productIds.stream()
            .distinct()
            .collect(Collectors.toMap(productId -> productId, productId -> counts.getOrDefault(productId, 0L)));
    }
}
