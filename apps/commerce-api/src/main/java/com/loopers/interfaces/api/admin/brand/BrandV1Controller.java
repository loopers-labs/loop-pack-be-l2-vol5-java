package com.loopers.interfaces.api.admin.brand;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.brand.BrandInfo;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
public class BrandV1Controller implements BrandV1ApiSpec {

    private final BrandFacade brandFacade;

    @GetMapping("/{brandId}")
    @Override
    public ApiResponse<BrandV1Dto.BrandResponse> getDetail(@PathVariable Long brandId) {
        BrandInfo info = brandFacade.getDetail(brandId);
        return ApiResponse.success(BrandV1Dto.BrandResponse.from(info));
    }

    @PutMapping("/{brandId}")
    @Override
    public ApiResponse<BrandV1Dto.BrandResponse> update(
        @PathVariable Long brandId,
        @RequestBody BrandV1Dto.UpdateRequest request
    ) {
        BrandInfo info = brandFacade.update(brandId, request.name());
        return ApiResponse.success(BrandV1Dto.BrandResponse.from(info));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Override
    public ApiResponse<BrandV1Dto.BrandResponse> register(@RequestBody BrandV1Dto.CreateRequest request) {
        BrandInfo info = brandFacade.register(request.name());
        return ApiResponse.success(BrandV1Dto.BrandResponse.from(info));
    }
}
