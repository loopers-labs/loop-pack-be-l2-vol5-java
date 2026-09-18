package com.loopers.application.like;

import com.loopers.domain.like.LikeService;
import com.loopers.domain.user.UserErrorCode;
import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Objects;

@RequiredArgsConstructor
@Component
public class LikeFacade {
    private final LikeService likeService;

    public LikeInfo like(Long userId, Long productId) {
        likeService.like(userId, productId);
        return new LikeInfo(productId, likeService.countLikes(productId));
    }

    /** 경로의 사용자가 요청자 본인이 아니면, 그 사용자의 존재를 드러내지 않도록 없는 대상으로 응답한다 (설계 6.1). */
    public Page<LikedProductInfo> getMyLikes(Long requesterId, Long userId, Pageable pageable) {
        if (!Objects.equals(requesterId, userId)) {
            throw new CoreException(UserErrorCode.USER_NOT_FOUND);
        }
        return likeService.getLikedProducts(userId, pageable).map(LikedProductInfo::from);
    }

    public LikeInfo unlike(Long userId, Long productId) {
        likeService.unlike(userId, productId);
        return new LikeInfo(productId, likeService.countLikes(productId));
    }
}
