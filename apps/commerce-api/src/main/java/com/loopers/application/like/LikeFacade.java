package com.loopers.application.like;

import com.loopers.application.product.ProductFacade;
import com.loopers.application.product.ProductInfo;
import com.loopers.domain.like.LikeModel;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductListRevisionService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRevisionService;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class LikeFacade {
    private final LikeService likeService;
    private final ProductService productService;
    private final ProductFacade productFacade;
    private final ProductRevisionService productRevisionService;
    private final ProductListRevisionService productListRevisionService;

    // 좋아요 수는 상품 캐시에 담기고 좋아요순 목록의 순서를 바꾸므로, 같은 트랜잭션에서 두 revision을 올린다
    @Transactional
    public void like(Long userId, Long productId) {
        ProductModel product = productService.getProduct(productId);
        likeService.like(userId, productId);
        increaseLikeRevisions(productId, product.getBrandId());
    }

    // 삭제된 상품은 목록에 나가지 않으므로 revision을 올리지 않는다
    @Transactional
    public void unlike(Long userId, Long productId) {
        likeService.unlike(userId, productId);
        productService.findActiveBrandId(productId).ifPresent(brandId -> increaseLikeRevisions(productId, brandId));
    }

    private void increaseLikeRevisions(Long productId, Long brandId) {
        productRevisionService.increase(productId);
        productListRevisionService.increaseForLikes(brandId, productId);
    }

    public Page<ProductInfo> getMyLikedProducts(Long userId, Pageable pageable) {
        Page<LikeModel> likes = likeService.getLikesByUser(userId, pageable);
        List<Long> productIds = likes.getContent().stream().map(LikeModel::getProductId).toList();
        Map<Long, ProductInfo> infoByProductId = productFacade.getProductsByIds(productIds).stream()
            .collect(Collectors.toMap(ProductInfo::id, Function.identity()));
        List<ProductInfo> content = productIds.stream()
            .map(infoByProductId::get)
            .filter(Objects::nonNull)
            .toList();
        return new PageImpl<>(content, pageable, likes.getTotalElements());
    }
}
