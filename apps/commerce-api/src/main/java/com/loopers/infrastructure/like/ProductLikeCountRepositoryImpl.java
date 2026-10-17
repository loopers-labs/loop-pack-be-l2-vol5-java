package com.loopers.infrastructure.like;

import com.loopers.domain.like.ProductLikeCountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class ProductLikeCountRepositoryImpl implements ProductLikeCountRepository {
    private final ProductLikeCountJpaRepository productLikeCountJpaRepository;

    @Override
    public void create(Long productId) {
        productLikeCountJpaRepository.insertZero(productId);
    }

    @Override
    public void deleteAll(Collection<Long> productIds) {
        // 빈 목록으로 IN 조건을 쓰면 DB 에 따라 문법 오류가 나므로 실행하지 않는다.
        if (productIds.isEmpty()) {
            return;
        }
        productLikeCountJpaRepository.deleteAllByProductIdIn(productIds);
    }

    @Override
    public int increase(Long productId) {
        return productLikeCountJpaRepository.increase(productId);
    }

    @Override
    public int decrease(Long productId) {
        return productLikeCountJpaRepository.decrease(productId);
    }

    @Override
    public long findCount(Long productId) {
        return productLikeCountJpaRepository.findLikeCount(productId).orElse(0L);
    }

    @Override
    public Map<Long, Long> findCounts(Collection<Long> productIds) {
        // 빈 목록으로 IN 조회를 하면 DB 에 따라 문법 오류가 나므로 조회하지 않는다.
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        for (ProductLikeCountRow row : productLikeCountJpaRepository.findLikeCounts(productIds)) {
            counts.put(row.productId(), row.likeCount());
        }
        return counts;
    }

    @Override
    public List<Long> findRankedProductIds(Pageable pageable) {
        return productLikeCountJpaRepository.findProductIdsOrderByLikeCountDesc(pageable);
    }
}
