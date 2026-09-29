package com.loopers.application.like;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.like.LikeModel;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class LikeFacade {

    private final LikeRepository likeRepository;
    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;

    /**
     * LIK-02·LIK-03: 팔 수 있는 상품에만 좋아요한다. 이미 좋아요했으면 아무것도 바뀌지 않는다.
     */
    @Transactional
    public void like(Long userId, Long productId) {
        ProductModel product = productRepository.findActiveById(productId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[productId = " + productId + "] 상품을 찾을 수 없습니다."));
        likeRepository.addIfAbsent(userId, product.getId(), ZonedDateTime.now());
    }

    /**
     * LIK-02·LIK-03: 관계가 있으면 지운다. 관계가 없거나 상품이 삭제됐어도 성공이다.
     */
    @Transactional
    public void unlike(Long userId, Long productId) {
        likeRepository.remove(userId, productId);
    }

    /**
     * LIK-05: 본인의 좋아요 목록만 조회하며, 팔 수 없는(삭제된) 상품은 뺀다.
     */
    @Transactional(readOnly = true)
    public List<LikedProductInfo> getLikedProducts(Long requesterId, Long userId) {
        if (!requesterId.equals(userId)) {
            throw new CoreException(ErrorType.NOT_FOUND, "좋아요 목록을 찾을 수 없습니다.");
        }
        List<LikeModel> likes = likeRepository.findAllByUserIdNewestFirst(userId);
        Map<Long, ProductModel> sellableProducts = productRepository
            .findAllByIds(likes.stream().map(LikeModel::getProductId).toList()).stream()
            .filter(ProductModel::isSellable)
            .collect(Collectors.toMap(ProductModel::getId, Function.identity()));
        Map<Long, BrandModel> brands = brandRepository
            .findAllByIds(sellableProducts.values().stream().map(ProductModel::getBrandId).distinct().toList()).stream()
            .collect(Collectors.toMap(BrandModel::getId, Function.identity()));

        return likes.stream()
            .filter(like -> sellableProducts.containsKey(like.getProductId()))
            .map(like -> {
                ProductModel product = sellableProducts.get(like.getProductId());
                return LikedProductInfo.of(like, product, brands.get(product.getBrandId()));
            })
            .toList();
    }
}
