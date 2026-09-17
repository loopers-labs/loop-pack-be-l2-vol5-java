package com.loopers.application.like;

import com.loopers.application.product.ProductInfo;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.like.LikeModel;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class LikeFacade {

    private final UserService userService;
    private final ProductService productService;
    private final BrandService brandService;
    private final LikeService likeService;

    public LikeInfo like(Long userId, Long productId) {
        userService.getUser(userId);
        productService.getActiveProduct(productId);
        likeService.like(userId, productId);
        return new LikeInfo(productId, likeService.countByProduct(productId));
    }

    public LikeInfo unlike(Long userId, Long productId) {
        userService.getUser(userId);
        likeService.unlike(userId, productId);
        return new LikeInfo(productId, likeService.countByProduct(productId));
    }

    public List<ProductInfo> getMyLikes(Long requesterId, Long pathUserId) {
        if (!pathUserId.equals(requesterId)) {
            throw new CoreException(ErrorType.NOT_FOUND, "[id = " + pathUserId + "] 사용자를 찾을 수 없습니다.");
        }
        userService.getUser(requesterId);

        List<Long> likedProductIds = likeService.getUserLikes(requesterId).stream()
            .map(LikeModel::getProductId)
            .toList();
        List<ProductModel> products = productService.getActiveProductsByIds(likedProductIds);

        List<Long> productIds = products.stream().map(ProductModel::getId).toList();
        Map<Long, Long> likeCounts = likeService.countByProducts(productIds);
        List<Long> brandIds = products.stream().map(ProductModel::getBrandId).distinct().toList();
        Map<Long, String> brandNames = brandService.getBrandNames(brandIds);

        return products.stream()
            .map(product -> ProductInfo.from(
                product,
                brandNames.get(product.getBrandId()),
                likeCounts.getOrDefault(product.getId(), 0L)
            ))
            .toList();
    }
}
