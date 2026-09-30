package com.loopers.interfaces.api.brand;

import com.loopers.application.brand.BrandAdminFacade;
import com.loopers.application.brand.BrandFacade;
import com.loopers.application.brand.BrandInfo;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/brands")
public class BrandAdminV1Controller implements BrandAdminV1ApiSpec {

    private final BrandFacade brandFacade;
    private final BrandAdminFacade brandAdminFacade;

    @GetMapping
    @Override
    public ApiResponse<BrandAdminV1Dto.BrandListResponse> getBrands() {
        return ApiResponse.success(BrandAdminV1Dto.BrandListResponse.from(brandFacade.getBrands()));
    }

    @GetMapping("/{brandId}")
    @Override
    public ApiResponse<BrandAdminV1Dto.BrandResponse> getBrand(
        @PathVariable(value = "brandId") Long brandId
    ) {
        BrandInfo info = brandFacade.getBrand(brandId);
        return ApiResponse.success(BrandAdminV1Dto.BrandResponse.from(info));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Override
    public ApiResponse<BrandAdminV1Dto.BrandResponse> createBrand(
        @RequestBody BrandAdminV1Dto.CreateRequest request
    ) {
        BrandInfo info = brandAdminFacade.createBrand(request.name(), request.description(), request.category());
        return ApiResponse.success(BrandAdminV1Dto.BrandResponse.from(info));
    }

    @PutMapping("/{brandId}")
    @Override
    public ApiResponse<BrandAdminV1Dto.BrandResponse> updateBrand(
        @PathVariable(value = "brandId") Long brandId,
        @RequestBody BrandAdminV1Dto.UpdateRequest request
    ) {
        BrandInfo info = brandAdminFacade.updateBrand(
            brandId, request.name(), request.description(), request.category()
        );
        return ApiResponse.success(BrandAdminV1Dto.BrandResponse.from(info));
    }

    @DeleteMapping("/{brandId}")
    @Override
    public ApiResponse<Object> deleteBrand(
        @PathVariable(value = "brandId") Long brandId
    ) {
        brandAdminFacade.deleteBrand(brandId);
        return ApiResponse.success();
    }
}
