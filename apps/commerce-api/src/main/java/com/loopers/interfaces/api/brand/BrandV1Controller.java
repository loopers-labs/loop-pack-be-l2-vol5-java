package com.loopers.interfaces.api.brand;

import com.loopers.application.brand.BrandQueryService;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.commerce.StrictInput;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/brands", produces = "application/json")
public class BrandV1Controller {

    private final BrandQueryService brandQueryService;

    public BrandV1Controller(BrandQueryService brandQueryService) {
        this.brandQueryService = brandQueryService;
    }

    @GetMapping("/{brandId}")
    public ApiResponse<BrandV1Dto.BrandView> getDetail(@PathVariable("brandId") String brandId) {
        return ApiResponse.success(BrandV1Dto.BrandView.from(brandQueryService.getDetail(StrictInput.id(brandId))));
    }
}
