package com.loopers.brand.interfaces;

import com.loopers.brand.application.BrandUseCase;
import com.loopers.support.cache.ResponseCache;
import com.loopers.support.cache.CacheKeys;
import com.loopers.interfaces.api.ListResponse;
import com.loopers.interfaces.api.PageQuery;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.web.bind.annotation.RequestParam;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.Requester;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/brands")
public class BrandV1Controller {

    private final BrandUseCase brandUseCase;

    private final ResponseCache cache;
    private final com.loopers.support.cache.CatalogReadTransaction cacheTransaction;

    @GetMapping
    public ApiResponse<ListResponse<BrandV1Dto.CustomerBrandResponse>> getBrands(
        Requester requester,
        @RequestParam(defaultValue = PageQuery.DEFAULT_PAGE) int page,
        @RequestParam(defaultValue = PageQuery.DEFAULT_SIZE) int size
    ) {
        var query = new PageQuery(page, size);
        return cacheTransaction.read(() -> cache.read(CacheKeys.BRANDS,
            CacheKeys.customerBrands(query.page(), query.size()),
            new TypeReference<>() { },
            () -> ApiResponse.success(ListResponse.from(
                brandUseCase.findActivePage(query.page(), query.size()), BrandV1Dto.CustomerBrandResponse::from))));
    }

    @GetMapping("/{brandId}")
    public ApiResponse<BrandV1Dto.CustomerBrandResponse> getBrand(
        Requester requester,
        @PathVariable Long brandId
    ) {
        return ApiResponse.success(BrandV1Dto.CustomerBrandResponse.from(brandUseCase.findActive(brandId)));
    }
}
