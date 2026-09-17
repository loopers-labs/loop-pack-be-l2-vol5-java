package com.loopers.interfaces.api.admin.brand;

import com.loopers.application.brand.AdminBrandFacade;
import com.loopers.interfaces.api.ApiResponse;
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
public class AdminBrandController implements AdminBrandApiSpec {

    private final AdminBrandFacade adminBrandFacade;

    @GetMapping
    @Override
    public ApiResponse<PageResponse<AdminBrandDto.BrandResponse>> getBrands(
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ApiResponse.success(PageResponse.from(adminBrandFacade.getBrands(page, size), AdminBrandDto.BrandResponse::from));
    }

    @GetMapping("/{brandId}")
    @Override
    public ApiResponse<AdminBrandDto.BrandResponse> getBrand(@PathVariable(value = "brandId") Long brandId) {
        return ApiResponse.success(AdminBrandDto.BrandResponse.from(adminBrandFacade.getBrand(brandId)));
    }

    @PostMapping
    @Override
    public ApiResponse<AdminBrandDto.BrandResponse> register(@RequestBody AdminBrandDto.BrandRequest request) {
        return ApiResponse.success(AdminBrandDto.BrandResponse.from(adminBrandFacade.register(request.name())));
    }

    @PutMapping("/{brandId}")
    @Override
    public ApiResponse<AdminBrandDto.BrandResponse> update(
        @PathVariable(value = "brandId") Long brandId,
        @RequestBody AdminBrandDto.BrandRequest request
    ) {
        return ApiResponse.success(AdminBrandDto.BrandResponse.from(adminBrandFacade.update(brandId, request.name())));
    }

    @DeleteMapping("/{brandId}")
    @Override
    public ApiResponse<Object> delete(@PathVariable(value = "brandId") Long brandId) {
        adminBrandFacade.delete(brandId);
        return ApiResponse.success();
    }
}
