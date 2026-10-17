package com.loopers.infrastructure.like;

import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeId;
import com.loopers.domain.like.LikeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class LikeRepositoryImpl implements LikeRepository {
    private final LikeJpaRepository likeJpaRepository;

    @Override
    public Like save(Like like) {
        return likeJpaRepository.save(like);
    }

    @Override
    public boolean exists(Long userId, Long productId) {
        return likeJpaRepository.existsById(new LikeId(productId, userId));
    }

    @Override
    public int delete(Long userId, Long productId) {
        return likeJpaRepository.deleteByUserIdAndProductId(userId, productId);
    }
}
