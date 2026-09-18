package com.loopers.interfaces.api.brand;

import com.loopers.application.brand.GetBrandFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class GetBrandController {
    private final GetBrandFacade facade;
    @GetMapping("/api/v1/brands/{brandId}")
    public ApiResponse<BrandDto.Customer> get(@PathVariable long brandId) {
        return ApiResponse.success(BrandDto.Customer.from(facade.customer(brandId)));
    }
}
