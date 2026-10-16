package com.loopers.product.interfaces;

import com.fasterxml.jackson.core.type.TypeReference;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.ListResponse;
import com.loopers.interfaces.api.SliceResponse;
import com.loopers.like.application.LikeUseCase;
import com.loopers.product.application.ProductUseCase;
import com.loopers.product.domain.ProductSort;
import com.loopers.support.cache.CacheKeys;
import com.loopers.support.cache.ResponseCache;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import com.loopers.support.cache.CatalogReadTransaction;

/** 상품 정보와 표시 값을 별도로 캐시하고 한 응답으로 합친다. */
@Component
@RequiredArgsConstructor
public class ProductReadFacade {
    private final ProductUseCase products;
    private final LikeUseCase likes;
    private final ResponseCache cache;
    private final CatalogReadTransaction transaction;

    public ApiResponse<SliceResponse<ProductV1Dto.CustomerProductResponse>> page(Long brandId, ProductSort sort, String normalized, int page, int size) {
        return transaction.read(() -> pageWithinRead(brandId, sort, normalized, page, size));
    }

    private ApiResponse<SliceResponse<ProductV1Dto.CustomerProductResponse>> pageWithinRead(Long brandId, ProductSort sort, String normalized, int page, int size) {
        ApiResponse<SliceResponse<ProductV1Dto.BasicProductResponse>> basic = cache.read(
            "likes_desc".equals(normalized) ? CacheKeys.RANKINGS : CacheKeys.PRODUCTS,
            CacheKeys.products(normalized, brandId, page, size), new TypeReference<>() { },
            () -> ApiResponse.success(SliceResponse.from(products.findBasicPage(brandId, sort, page, size), ProductV1Dto.BasicProductResponse::from)));
        var data = basic.data();
        return ApiResponse.success(new SliceResponse<>(compose(data.content()), data.page(), data.size(), data.last()));
    }

    public ApiResponse<ProductV1Dto.CustomerProductResponse> detail(Long id) {
        return transaction.read(() -> detailWithinRead(id));
    }

    private ApiResponse<ProductV1Dto.CustomerProductResponse> detailWithinRead(Long id) {
        ApiResponse<ProductV1Dto.BasicProductResponse> basic = cache.read( CacheKeys.PRODUCTS, CacheKeys.product(id), new TypeReference<>() { },
            () -> ApiResponse.success(ProductV1Dto.BasicProductResponse.from(products.findBasicProduct(id))));
        return ApiResponse.success(compose(List.of(basic.data())).get(0));
    }

    public ApiResponse<ListResponse<ProductV1Dto.CustomerProductResponse>> mine(Long userId, int page, int size) {
        return transaction.read(() -> mineWithinRead(userId, page, size));
    }

    private ApiResponse<ListResponse<ProductV1Dto.CustomerProductResponse>> mineWithinRead(Long userId, int page, int size) {
        ApiResponse<ListResponse<ProductV1Dto.BasicProductResponse>> basic = cache.read( CacheKeys.MY_LIKES,
            CacheKeys.myLikes(userId, page, size), new TypeReference<>() { },
            () -> ApiResponse.success(ListResponse.from(likes.findMineBasicPage(userId, userId, page, size), ProductV1Dto.BasicProductResponse::from)));
        var data = basic.data();
        return ApiResponse.success(new ListResponse<>(compose(data.content()), data.page(), data.size(), data.totalElements()));
    }

    public ApiResponse<List<ProductUseCase.LikeCount>> counts(List<Long> ids) {
        return transaction.read(() -> ApiResponse.success(cache.readBatch(countBatch(ids))));
    }
    public ApiResponse<List<ProductUseCase.StockStatus>> stocks(List<Long> ids) {
        return transaction.read(() -> ApiResponse.success(cache.readBatch(stockBatch(ids))));
    }

    private ResponseCache.Batch<ProductUseCase.LikeCount> countBatch(List<Long> ids) {
        return new ResponseCache.Batch<>(CacheKeys.COUNTS, ids.stream().distinct().sorted().toList(), new TypeReference<>() { },
            products::findLikeCounts, ProductUseCase.LikeCount::productId);
    }

    private ResponseCache.Batch<ProductUseCase.StockStatus> stockBatch(List<Long> ids) {
        return new ResponseCache.Batch<>(CacheKeys.STOCKS, ids.stream().distinct().sorted().toList(), new TypeReference<>() { },
            products::findStockStatuses, ProductUseCase.StockStatus::productId);
    }

    private List<ProductV1Dto.CustomerProductResponse> compose(List<ProductV1Dto.BasicProductResponse> basic) {
        if (basic.isEmpty()) return List.of();
        var ids = basic.stream().map(ProductV1Dto.BasicProductResponse::id).distinct().sorted().toList();
        var values = cache.readBatches( countBatch(ids), stockBatch(ids));
        var counts = values.first().stream().collect(Collectors.toMap(ProductUseCase.LikeCount::productId, Function.identity()));
        var stocks = values.second().stream().collect(Collectors.toMap(ProductUseCase.StockStatus::productId, Function.identity()));
        return basic.stream().map(item -> new ProductV1Dto.CustomerProductResponse(item.id(), item.name(), item.price(), item.brand(),
            counts.get(item.id()).likeCount(), stocks.get(item.id()).soldOut())).toList();
    }
}
