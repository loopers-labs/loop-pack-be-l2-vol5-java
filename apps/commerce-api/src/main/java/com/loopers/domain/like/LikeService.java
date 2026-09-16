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
}
