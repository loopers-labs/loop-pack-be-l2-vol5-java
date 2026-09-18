package com.loopers.interfaces.api.brand;

import com.loopers.application.brand.GetAdminBrandFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class GetAdminBrandController {
    private final GetAdminBrandFacade facade;
    @GetMapping("/api-admin/v1/brands/{brandId}")
    public ApiResponse<BrandDto.Admin> get(@PathVariable long brandId) {
        return ApiResponse.success(BrandDto.Admin.from(facade.admin(brandId)));
    }
    @GetMapping("/api-admin/v1/brands")
    public ApiResponse<PageResponse<BrandDto.Admin>> list(@RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(PageResponse.from(facade.list(page, size).map(BrandDto.Admin::from)));
    }
}
