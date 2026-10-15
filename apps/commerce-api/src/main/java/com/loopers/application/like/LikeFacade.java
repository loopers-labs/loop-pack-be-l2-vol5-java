package com.loopers.application.like;

import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// 좋아요 등록·취소는 상품 캐시를 무효화하지 않는다 — 가장 잦은 쓰기라 지우면 캐시가 계속 비어 DB로 요청이 몰린다.
// likeCount·likes_desc 순서는 TTL(60초) 안에 늦게 반영되는 것으로 계약을 바꿨다 (docs/week4/design.md 6번 섹션 "TTL 60초와 필드별 신선도").
@RequiredArgsConstructor
@Component
public class LikeFacade {

    private final LikeService likeService;
    private final ProductService productService;

    public void likeProduct(Long userId, Long productId) {
        productService.getProduct(productId);
        likeService.register(userId, productId);
    }

    public void unlikeProduct(Long userId, Long productId) {
        likeService.cancel(userId, productId);
    }

    /**
     * 좋아요 행은 상품이 삭제돼도 남긴다 — "이 유저가 좋아했다"는 사실은 상품의 판매 여부와 무관하다.
     * 대신 읽는 시점에 삭제된 상품을 거른다 (docs/week3/design.md 5번 섹션). getProductsByIds가 IN 쿼리 한 번으로
     * 삭제·부재 상품을 걸러주므로, 좋아요 순서는 유지한 채 살아있는 상품만 남긴다.
     */
    public LikeListInfo getMyLikes(Long requesterId, Long userId) {
        List<Long> likedProductIds = likeService.getMyLikedProductIds(requesterId, userId);
        Set<Long> activeProductIds = productService.getProductsByIds(likedProductIds).stream()
            .map(ProductModel::getId)
            .collect(Collectors.toSet());
        return LikeListInfo.from(likedProductIds.stream()
            .filter(activeProductIds::contains)
            .toList());
    }
}
