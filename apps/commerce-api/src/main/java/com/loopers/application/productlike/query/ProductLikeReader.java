package com.loopers.application.productlike.query;

import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 좋아요 조회 유스케이스 (DR-31). */
@RequiredArgsConstructor
@Component
public class ProductLikeReader {
    private final UserService userService;
    private final ProductLikeQueryRepository productLikeQueryRepository;

    /** FR-LIKE-03 내 좋아요 목록. userId ≠ 요청자면 NOT_OWNER (ASM-09). 삭제된 상품 제외 (ASM-07). */
    @Transactional(readOnly = true)
    public PageResult<ProductLikeView.Item> listMyLikes(Long requesterId, Long userId, PageQuery query) {
        userService.getUser(requesterId);
        if (!requesterId.equals(userId)) {
            throw new CoreException(ErrorType.NOT_OWNER, "본인의 좋아요 목록만 조회할 수 있습니다.");
        }
        return productLikeQueryRepository.findPageByUserId(userId, query);
    }
}
