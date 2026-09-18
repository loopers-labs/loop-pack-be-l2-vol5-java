package com.loopers.domain.like;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** DB 없이 좋아요 서비스의 협력을 확인하기 위한 저장 구현. */
public class FakeLikeRepository implements LikeRepository {

    private final List<Like> likes = new ArrayList<>();
    private long sequence = 0L;

    @Override
    public Like save(Like like) {
        ReflectionTestUtils.setField(like, "id", ++sequence);
        likes.add(like);
        return like;
    }

    @Override
    public Optional<Like> find(Long userId, Long productId) {
        return likes.stream()
            .filter(like -> Objects.equals(like.getUserId(), userId) && Objects.equals(like.getProductId(), productId))
            .findFirst();
    }

    @Override
    public void delete(Like like) {
        likes.remove(like);
    }

    @Override
    public long countByProductId(Long productId) {
        return likes.stream().filter(like -> Objects.equals(like.getProductId(), productId)).count();
    }

    // 상품 · 브랜드와 조인하는 조회는 저장소 통합 테스트(실제 DB)에서 확인한다
    @Override
    public Page<LikedProduct> findLikedProducts(Long userId, Pageable pageable) {
        throw new UnsupportedOperationException("LikeRepositoryIntegrationTest 에서 확인한다");
    }
}
