package com.loopers.like.application;

import com.loopers.brand.application.port.out.BrandPort;
import com.loopers.brand.domain.BrandModel;
import com.loopers.like.application.port.in.LikedProductInfo;
import com.loopers.like.application.port.out.LikePort;
import com.loopers.like.domain.LikeModel;
import com.loopers.product.application.port.out.ProductPort;
import com.loopers.product.domain.ProductModel;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 좋아요 목록 조회. 지킬 규칙이 없어 입력 포트 없이 웹 어댑터가 직접 부른다.
 */
@RequiredArgsConstructor
@Component
@Transactional(readOnly = true)
public class LikeQueryService {

    private final LikePort likePort;
    private final ProductPort productPort;
    private final BrandPort brandPort;

    /**
     * LIK-05: 본인의 좋아요 목록만 조회하며, 팔 수 없는(삭제된) 상품은 뺀다.
     */
    public List<LikedProductInfo> getLikedProducts(Long requesterId, Long userId) {
        if (!requesterId.equals(userId)) {
            throw new CoreException(ErrorType.NOT_FOUND, "좋아요 목록을 찾을 수 없습니다.");
        }
        List<LikeModel> likes = likePort.findAllByUserIdNewestFirst(userId);
        Map<Long, ProductModel> sellableProducts = productPort
            .findAllByIds(likes.stream().map(LikeModel::getProductId).toList()).stream()
            .filter(ProductModel::isSellable)
            .collect(Collectors.toMap(ProductModel::getId, Function.identity()));
        Map<Long, BrandModel> brands = brandPort
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
