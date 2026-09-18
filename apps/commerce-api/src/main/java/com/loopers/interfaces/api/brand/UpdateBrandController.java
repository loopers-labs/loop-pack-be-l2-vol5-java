package com.loopers.interfaces.api.brand;

import com.loopers.application.brand.UpdateBrandFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequiredArgsConstructor
public class UpdateBrandController {
    private final UpdateBrandFacade facade;
    @PutMapping("/api-admin/v1/brands/{brandId}")
    public ApiResponse<BrandDto.Admin> update(@PathVariable long brandId, @RequestBody BrandDto.Request request) {
        return ApiResponse.success(BrandDto.Admin.from(facade.update(brandId, request.name())));
    }
}
