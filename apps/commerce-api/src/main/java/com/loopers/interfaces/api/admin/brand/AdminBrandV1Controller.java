package com.loopers.interfaces.api.admin.brand;

import com.loopers.application.brand.BrandFacade;
import com.loopers.domain.brand.Brand;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.brand.BrandV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/brands")
public class AdminBrandV1Controller {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final BrandFacade brandFacade;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BrandV1Dto.BrandResponse> createBrand(
        @RequestBody AdminBrandV1Dto.CreateRequest request
    ) {
        Brand brand = brandFacade.createBrand(request.name());
        return ApiResponse.success(BrandV1Dto.BrandResponse.from(brand));
    }

    @GetMapping
    public ApiResponse<List<BrandV1Dto.BrandResponse>> getBrands(
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size
    ) {
        List<BrandV1Dto.BrandResponse> response = brandFacade.getBrandsForAdmin(page, size).stream()
            .map(BrandV1Dto.BrandResponse::from)
            .toList();

        return ApiResponse.success(response);
    }

    @GetMapping("/{brandId}")
    public ApiResponse<BrandV1Dto.BrandResponse> getBrand(
        @PathVariable(value = "brandId") Long brandId
    ) {
        Brand brand = brandFacade.getBrand(brandId);
        return ApiResponse.success(BrandV1Dto.BrandResponse.from(brand));
    }

    @PutMapping("/{brandId}")
    public ApiResponse<BrandV1Dto.BrandResponse> updateBrand(
        @PathVariable(value = "brandId") Long brandId,
        @RequestBody AdminBrandV1Dto.UpdateRequest request
    ) {
        Brand brand = brandFacade.updateBrand(brandId, request.name());
        return ApiResponse.success(BrandV1Dto.BrandResponse.from(brand));
    }

    @DeleteMapping("/{brandId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBrand(
        @PathVariable(value = "brandId") Long brandId
    ) {
        brandFacade.deleteBrand(brandId);
    }
}
