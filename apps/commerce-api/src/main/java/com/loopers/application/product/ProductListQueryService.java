package com.loopers.application.product;

import com.loopers.domain.like.LikeService;
import com.loopers.domain.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ProductListQueryService {
    private final ProductQueryService products;
    private final UserService users;
    private final LikeService likes;

    @Transactional(readOnly = true)
    public ProductPage<ProductListItemInfo> getList(Long requesterId, Long brandId, int page, int size, ProductSort sort) {
        if (requesterId != null) {
            users.get(requesterId);
        }
        var common = products.getList(brandId, page, size, sort);
        Set<Long> likedIds = likedProductIds(requesterId, common.items());
        var items = common.items().stream()
            .map(product -> new ProductListItemInfo(product, likedIds.contains(product.id())))
            .toList();
        return new ProductPage<>(items, common.page(), common.size());
    }

    private Set<Long> likedProductIds(Long requesterId, List<CustomerProductInfo> page) {
        if (requesterId == null) {
            return Set.of();
        }
        return likes.findLikedProductIds(requesterId, page.stream().map(CustomerProductInfo::id).toList());
    }
}
