package com.loopers.brand.interfaces;

import com.loopers.brand.application.BrandUseCase;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.ListResponse;
import com.loopers.interfaces.api.PageQuery;
import com.loopers.interfaces.api.RequestFields;
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

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/brands")
public class BrandAdminV1Controller {

    private final BrandUseCase brandUseCase;

    @GetMapping
    public ApiResponse<ListResponse<BrandV1Dto.AdminBrandResponse>> getBrands(
        @RequestParam(defaultValue = PageQuery.DEFAULT_PAGE) int page,
        @RequestParam(defaultValue = PageQuery.DEFAULT_SIZE) int size
    ) {
        PageQuery query = new PageQuery(page, size);
        return ApiResponse.success(ListResponse.from(
            brandUseCase.findPage(query.page(), query.size()),
            BrandV1Dto.AdminBrandResponse::from
        ));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BrandV1Dto.AdminBrandResponse> createBrand(@RequestBody BrandV1Dto.BrandRequest request) {
        String name = RequestFields.required(request.name());
        return ApiResponse.success(BrandV1Dto.AdminBrandResponse.from(brandUseCase.create(name)));
    }

    @GetMapping("/{brandId}")
    public ApiResponse<BrandV1Dto.AdminBrandResponse> getBrand(@PathVariable Long brandId) {
        return ApiResponse.success(BrandV1Dto.AdminBrandResponse.from(brandUseCase.find(brandId)));
    }

    @PutMapping("/{brandId}")
    public ApiResponse<BrandV1Dto.AdminBrandResponse> updateBrand(
        @PathVariable Long brandId,
        @RequestBody BrandV1Dto.BrandRequest request
    ) {
        String name = RequestFields.required(request.name());
        return ApiResponse.success(BrandV1Dto.AdminBrandResponse.from(brandUseCase.update(brandId, name)));
    }

    @DeleteMapping("/{brandId}")
    public ApiResponse<Object> deleteBrand(@PathVariable Long brandId) {
        brandUseCase.delete(brandId);
        return ApiResponse.success();
    }
}
