package com.loopers.application.catalog;

import com.loopers.domain.catalog.ProductLikeModel;
import com.loopers.domain.catalog.ProductLikeService;
import com.loopers.domain.catalog.ProductModel;
import com.loopers.domain.catalog.ProductService;
import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class ProductLikeFacade {
    private final UserService userService;
    private final ProductService productService;
    private final ProductLikeService productLikeService;
    private final ProductInfoAssembler assembler;

    /** FR-LIKE-01 좋아요 등록. 상품이 없거나 삭제됨이면 PRODUCT_NOT_FOUND (ASM-07). 멱등 (ASM-06). */
    @Transactional
    public void like(Long requesterId, Long productId) {
        userService.getUser(requesterId);
        productService.getActive(productId);
        productLikeService.like(requesterId, productId);
    }

    /** FR-LIKE-02 좋아요 취소. 상품 존재만 묻고 삭제 여부는 묻지 않는다 (원문). 멱등 (ASM-06). */
    @Transactional
    public void unlike(Long requesterId, Long productId) {
        userService.getUser(requesterId);
        productService.get(productId);
        productLikeService.unlike(requesterId, productId);
    }

    /** FR-LIKE-03 내 좋아요 목록. userId ≠ 요청자면 NOT_OWNER (ASM-09). 삭제된 상품 제외 (ASM-07). */
    @Transactional(readOnly = true)
    public PageResult<LikeInfo> listMyLikes(Long requesterId, Long userId, PageQuery query) {
        userService.getUser(requesterId);
        if (!requesterId.equals(userId)) {
            throw new CoreException(ErrorType.NOT_OWNER, "본인의 좋아요 목록만 조회할 수 있습니다.");
        }
        PageResult<ProductLikeModel> page = productLikeService.listMyLikes(userId, query);
        List<Long> productIds = page.items().stream().map(ProductLikeModel::getProductId).toList();
        List<ProductModel> products = productService.getActiveProducts(productIds);
        Map<Long, ProductInfo> infos = assembler.assemble(products).stream()
            .collect(Collectors.toMap(ProductInfo::id, Function.identity()));
        List<LikeInfo> items = page.items().stream()
            .map(like -> new LikeInfo(like.getCreatedAt(), infos.get(like.getProductId())))
            .toList();
        return PageResult.of(items, query, page.totalCount());
    }
}
