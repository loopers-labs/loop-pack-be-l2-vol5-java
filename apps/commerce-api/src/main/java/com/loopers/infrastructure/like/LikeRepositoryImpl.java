package com.loopers.infrastructure.like;

import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.List;

@RequiredArgsConstructor
@Component
public class LikeRepositoryImpl implements LikeRepository {

    private final LikeJpaRepository likeJpaRepository;

    @Override
    public Optional<Like> findByUserIdAndProductId(Long userId, Long productId) {
        return likeJpaRepository.findByUserIdAndProductId(userId, productId).map(LikeJpaMapper::toDomain);
    }

    @Override
    public List<Like> findAllByUserId(Long userId) {
        return likeJpaRepository.findAllByUserId(userId).stream().map(LikeJpaMapper::toDomain).toList();
    }

    @Override
    public long countByProductId(Long productId) {
        return likeJpaRepository.countByProductId(productId);
    }

    @Override
    public Like save(Like like) {
        LikeJpaEntity entity = likeJpaRepository.findByUserIdAndProductId(like.getUserId(), like.getProductId())
            .orElseGet(() -> LikeJpaMapper.toNewEntity(like));
        return LikeJpaMapper.toDomain(likeJpaRepository.save(entity));
    }

    @Override
    public void delete(Like like) {
        likeJpaRepository.findByUserIdAndProductId(like.getUserId(), like.getProductId())
            .ifPresent(likeJpaRepository::delete);
    }
}
