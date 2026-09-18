package com.loopers.interfaces.api.admin.brand;

import com.loopers.application.brand.BrandFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageQuery;
import com.loopers.interfaces.api.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/brands")
public class AdminBrandV1Controller implements AdminBrandV1ApiSpec {

    private final BrandFacade brandFacade;

    @GetMapping
    @Override
    public ApiResponse<PageResponse<AdminBrandV1Dto.BrandResponse>> getBrands(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(PageResponse.from(
            brandFacade.getBrands(PageQuery.of(page, size)),
            AdminBrandV1Dto.BrandResponse::from
        ));
    }

    @PostMapping
    @Override
    public ApiResponse<AdminBrandV1Dto.BrandResponse> createBrand(@RequestBody AdminBrandV1Dto.BrandRequest request) {
        return ApiResponse.success(AdminBrandV1Dto.BrandResponse.from(
            brandFacade.createBrand(request.name(), request.description())
        ));
    }

    @GetMapping("/{brandId}")
    @Override
    public ApiResponse<AdminBrandV1Dto.BrandResponse> getBrand(@PathVariable Long brandId) {
        return ApiResponse.success(AdminBrandV1Dto.BrandResponse.from(brandFacade.getBrand(brandId)));
    }

    @PutMapping("/{brandId}")
    @Override
    public ApiResponse<AdminBrandV1Dto.BrandResponse> updateBrand(
        @PathVariable Long brandId,
        @RequestBody AdminBrandV1Dto.BrandRequest request
    ) {
        return ApiResponse.success(AdminBrandV1Dto.BrandResponse.from(
            brandFacade.updateBrand(brandId, request.name(), request.description())
        ));
    }

    @DeleteMapping("/{brandId}")
    @Override
    public ApiResponse<Object> deleteBrand(@PathVariable Long brandId) {
        brandFacade.deleteBrand(brandId);
        return ApiResponse.success();
    }
}
