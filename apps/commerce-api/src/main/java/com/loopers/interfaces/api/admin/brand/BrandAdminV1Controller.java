package com.loopers.interfaces.api.admin.brand;

import com.loopers.application.brand.BrandFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageQuery;
import com.loopers.interfaces.api.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
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
public class BrandAdminV1Controller implements BrandAdminV1ApiSpec {

    private final BrandFacade brandFacade;

    @GetMapping
    @Override
    public ApiResponse<PageResponse<BrandAdminV1Dto.BrandResponse>> getBrands(
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size
    ) {
        var pageable = PageQuery.of(page, size).toPageable(Sort.by(Sort.Direction.DESC, "id"));
        return ApiResponse.success(PageResponse.from(brandFacade.getBrands(pageable), BrandAdminV1Dto.BrandResponse::from));
    }

    @PostMapping
    @Override
    public ApiResponse<BrandAdminV1Dto.BrandResponse> createBrand(@RequestBody BrandAdminV1Dto.CreateRequest request) {
        return ApiResponse.success(BrandAdminV1Dto.BrandResponse.from(brandFacade.create(request.name(), request.description())));
    }

    @GetMapping("/{brandId}")
    @Override
    public ApiResponse<BrandAdminV1Dto.BrandResponse> getBrand(@PathVariable Long brandId) {
        return ApiResponse.success(BrandAdminV1Dto.BrandResponse.from(brandFacade.getBrand(brandId)));
    }

    @PutMapping("/{brandId}")
    @Override
    public ApiResponse<BrandAdminV1Dto.BrandResponse> updateBrand(
        @PathVariable Long brandId,
        @RequestBody BrandAdminV1Dto.UpdateRequest request
    ) {
        return ApiResponse.success(
            BrandAdminV1Dto.BrandResponse.from(brandFacade.update(brandId, request.name(), request.description()))
        );
    }
}
