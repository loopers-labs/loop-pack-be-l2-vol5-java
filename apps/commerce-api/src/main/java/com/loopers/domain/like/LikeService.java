package com.loopers.domain.like;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class LikeService {

    private final LikeRepository likeRepository;
    private final ProductLikeCountRepository productLikeCountRepository;

    /** 좋아요를 등록한다. 이미 있으면 그대로 두어 관계는 하나만 유지되고 좋아요 수도 늘지 않는다. */
    public void like(Long userId, Long productId) {
        if (likeRepository.exists(userId, productId)) {
            return;
        }
        likeRepository.save(Like.create(userId, productId));
        // 동시에 같은 관계를 등록하면 기본키 충돌로 한쪽 TX 가 rollback 되어 이 증가도 함께 취소된다.
        productLikeCountRepository.increase(productId);
    }

    /** 좋아요를 취소한다. 관계가 없어도 성공하며, 실제로 지웠을 때만 좋아요 수를 내린다. */
    public void unlike(Long userId, Long productId) {
        if (likeRepository.delete(userId, productId) > 0) {
            productLikeCountRepository.decrease(productId);
        }
    }

    /** 새 상품의 좋아요 수를 0으로 시작한다. 상품을 만드는 유스케이스가 같은 트랜잭션에서 호출해야 좋아요순 목록에 포함된다. */
    public void createCount(Long productId) {
        productLikeCountRepository.create(productId);
    }

    /** 삭제된 상품들의 좋아요 수를 지운다. 상품을 삭제하는 유스케이스가 같은 트랜잭션에서 호출해야 좋아요순 목록에서 빠진다. */
    public void removeCounts(Collection<Long> productIds) {
        productLikeCountRepository.deleteAll(productIds);
    }

    public long count(Long productId) {
        return productLikeCountRepository.findCount(productId);
    }

    /** 요청한 모든 상품의 좋아요 수를 돌려준다. 좋아요가 없는 상품은 0으로 채운다. */
    public Map<Long, Long> countByProductIds(Collection<Long> productIds) {
        Map<Long, Long> counts = productLikeCountRepository.findCounts(productIds);
        Map<Long, Long> result = new LinkedHashMap<>();
        for (Long productId : productIds) {
            result.put(productId, counts.getOrDefault(productId, 0L));
        }
        return result;
    }
}
