package com.loopers.interfaces.api.brand;

import com.loopers.application.brand.BrandFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.interfaces.api.auth.RequesterId;
import com.loopers.support.paging.PageQuery;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
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

    @GetMapping
    @Override
    public ApiResponse<PageResponse<BrandAdminV1Dto.BrandAdminResponse>> listBrands(
        @RequesterId Long requesterId,
        @ParameterObject PageQuery pageQuery
    ) {
        var result = brandFacade.listBrandsForAdmin(requesterId, pageQuery);
        return ApiResponse.success(PageResponse.from(result, BrandAdminV1Dto.BrandAdminResponse::from));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Override
    public ApiResponse<BrandAdminV1Dto.BrandAdminResponse> createBrand(
        @RequesterId Long requesterId,
        @RequestBody BrandAdminV1Dto.BrandRequest request
    ) {
        return ApiResponse.success(BrandAdminV1Dto.BrandAdminResponse.from(brandFacade.createBrand(requesterId, request.name())));
    }

    @GetMapping("/{brandId}")
    @Override
    public ApiResponse<BrandAdminV1Dto.BrandAdminResponse> getBrand(
        @RequesterId Long requesterId,
        @PathVariable("brandId") Long brandId
    ) {
        return ApiResponse.success(BrandAdminV1Dto.BrandAdminResponse.from(brandFacade.getBrandForAdmin(requesterId, brandId)));
    }

    @PutMapping("/{brandId}")
    @Override
    public ApiResponse<BrandAdminV1Dto.BrandAdminResponse> updateBrand(
        @RequesterId Long requesterId,
        @PathVariable("brandId") Long brandId,
        @RequestBody BrandAdminV1Dto.BrandRequest request
    ) {
        return ApiResponse.success(
            BrandAdminV1Dto.BrandAdminResponse.from(brandFacade.updateBrand(requesterId, brandId, request.name())));
    }

    @DeleteMapping("/{brandId}")
    @Override
    public ApiResponse<Object> deleteBrand(
        @RequesterId Long requesterId,
        @PathVariable("brandId") Long brandId
    ) {
        brandFacade.deleteBrand(requesterId, brandId);
        return ApiResponse.success();
    }
}
