package com.loopers.infrastructure.like;

import com.loopers.domain.like.LikeModel;
import com.loopers.domain.like.LikeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;

@RequiredArgsConstructor
@Component
public class LikeRepositoryImpl implements LikeRepository {

    private final LikeJpaRepository likeJpaRepository;

    @Override
    public void addIfAbsent(Long userId, Long productId, ZonedDateTime likedAt) {
        likeJpaRepository.insertIgnoringDuplicate(userId, productId, likedAt);
    }

    @Override
    public void remove(Long userId, Long productId) {
        likeJpaRepository.deleteByUserIdAndProductId(userId, productId);
    }

    @Override
    public List<LikeModel> findAllByUserIdNewestFirst(Long userId) {
        return likeJpaRepository.findAllByUserIdOrderByIdDesc(userId);
    }
}
