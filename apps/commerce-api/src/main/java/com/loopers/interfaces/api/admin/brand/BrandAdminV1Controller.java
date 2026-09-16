package com.loopers.interfaces.api.admin.brand;

import com.loopers.application.brand.BrandFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/brands")
public class BrandAdminV1Controller {

    private final BrandFacade brandFacade;

    @GetMapping
    public ApiResponse<List<BrandAdminV1Dto.BrandResponse>> getBrands() {
        return ApiResponse.success(
            brandFacade.getBrandsForAdmin().stream().map(BrandAdminV1Dto.BrandResponse::from).toList()
        );
    }

    @PostMapping
    public ApiResponse<BrandAdminV1Dto.BrandResponse> createBrand(
        @RequestBody BrandAdminV1Dto.CreateRequest request
    ) {
        return ApiResponse.success(BrandAdminV1Dto.BrandResponse.from(brandFacade.createBrand(request.name())));
    }

    @GetMapping("/{brandId}")
    public ApiResponse<BrandAdminV1Dto.BrandResponse> getBrand(@PathVariable(value = "brandId") Long brandId) {
        return ApiResponse.success(BrandAdminV1Dto.BrandResponse.from(brandFacade.getBrandForAdmin(brandId)));
    }

    @PutMapping("/{brandId}")
    public ApiResponse<BrandAdminV1Dto.BrandResponse> updateBrand(
        @PathVariable(value = "brandId") Long brandId,
        @RequestBody BrandAdminV1Dto.UpdateRequest request
    ) {
        return ApiResponse.success(
            BrandAdminV1Dto.BrandResponse.from(brandFacade.updateBrand(brandId, request.name()))
        );
    }

    @DeleteMapping("/{brandId}")
    public ApiResponse<Object> deleteBrand(@PathVariable(value = "brandId") Long brandId) {
        brandFacade.deleteBrand(brandId);
        return ApiResponse.success();
    }
}
