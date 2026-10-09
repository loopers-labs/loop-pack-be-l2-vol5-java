package com.loopers.infrastructure.persistence.shopping.repository;

import com.loopers.domain.shopping.model.Like;
import com.loopers.domain.shopping.repository.LikeRepository;
import com.loopers.infrastructure.persistence.shopping.jpa.LikeJpaRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
// LikeRepository JPA 구현체
public class LikeRepositoryImpl implements LikeRepository {
    private final LikeJpaRepository likeJpaRepository;

    // 좋아요 저장 (중복이면 무시)
    @Override
    public boolean save(Like like) {
        return likeJpaRepository.insertIfAbsent(like.getUserId(), like.getProductId(), Instant.now()) > 0;
    }

    // 좋아요 삭제 (실제로 지웠으면 true)
    @Override
    public boolean delete(long userId, long productId) {
        return likeJpaRepository.deleteByUserIdAndProductId(userId, productId) > 0;
    }
}
