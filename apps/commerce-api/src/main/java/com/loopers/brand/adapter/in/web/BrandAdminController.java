package com.loopers.brand.adapter.in.web;

import com.loopers.brand.adapter.in.web.dto.BrandAdminDto;
import com.loopers.brand.adapter.in.web.spec.BrandAdminApiSpec;
import com.loopers.brand.application.BrandQueryService;
import com.loopers.brand.application.port.in.BrandCommandUseCase;
import com.loopers.support.web.ApiResponse;
import com.loopers.support.web.PageQuery;
import com.loopers.support.web.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
public class BrandAdminController implements BrandAdminApiSpec {

    private final BrandCommandUseCase brandCommandUseCase;
    private final BrandQueryService brandQueryService;

    @GetMapping
    @Override
    public ApiResponse<PageResponse<BrandAdminDto.BrandResponse>> getBrands(
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size
    ) {
        Pageable pageable = PageQuery.of(page, size).toPageable(Sort.by(Sort.Direction.DESC, "id"));
        return ApiResponse.success(PageResponse.from(brandQueryService.getBrands(pageable), BrandAdminDto.BrandResponse::from));
    }

    @PostMapping
    @Override
    public ApiResponse<BrandAdminDto.BrandResponse> createBrand(@RequestBody BrandAdminDto.CreateRequest request) {
        return ApiResponse.success(BrandAdminDto.BrandResponse.from(brandCommandUseCase.create(request.name(), request.description())));
    }

    @GetMapping("/{brandId}")
    @Override
    public ApiResponse<BrandAdminDto.BrandResponse> getBrand(@PathVariable Long brandId) {
        return ApiResponse.success(BrandAdminDto.BrandResponse.from(brandQueryService.getBrand(brandId)));
    }

    @PutMapping("/{brandId}")
    @Override
    public ApiResponse<BrandAdminDto.BrandResponse> updateBrand(
        @PathVariable Long brandId,
        @RequestBody BrandAdminDto.UpdateRequest request
    ) {
        return ApiResponse.success(
            BrandAdminDto.BrandResponse.from(brandCommandUseCase.update(brandId, request.name(), request.description()))
        );
    }

    @DeleteMapping("/{brandId}")
    @Override
    public ApiResponse<Object> deleteBrand(@PathVariable Long brandId) {
        brandCommandUseCase.delete(brandId);
        return ApiResponse.success();
    }
}
