package com.loopers.domain.like;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class LikeService {

    private final LikeRepository likeRepository;

    /**
     * 관계를 실제로 만들었으면 true. 이미 있는 관계면 그대로 두고 false 다 (LIK-02, 멱등).
     * 살아 있는 상품인지(LIK-01)는 조율하는 LikeFacade 가 먼저 확인함.
     * 같은 관계를 동시에 만들면 늦은 쪽은 유니크 키 위반으로 실패하며, 다시 실행은 LikeRetrier 가 함 (4주차 설계 3.2)
     */
    @Transactional
    public boolean like(Long userId, Long productId) {
        if (likeRepository.find(userId, productId).isPresent()) {
            return false;
        }
        likeRepository.save(new Like(userId, productId));
        return true;
    }

    /**
     * 상품의 상태를 보지 않고 요청자 자신의 관계만 지운다. 없으면 이미 원하는 상태라 false 다 (LIK-03, 멱등).
     * 미리 읽은 결과가 아니라 지운 행 수로 판단해, 동시에 취소해도 한 번만 true 다 (4주차 설계 3.2)
     */
    @Transactional
    public boolean unlike(Long userId, Long productId) {
        return likeRepository.delete(userId, productId) == 1;
    }

    @Transactional(readOnly = true)
    public Page<LikedProduct> getLikedProducts(Long userId, Pageable pageable) {
        return likeRepository.findLikedProducts(userId, pageable);
    }
}
