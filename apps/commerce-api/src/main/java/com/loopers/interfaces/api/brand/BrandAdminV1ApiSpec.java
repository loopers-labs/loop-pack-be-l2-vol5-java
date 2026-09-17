package com.loopers.interfaces.api.brand;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Admin Brand V1 API", description = "관리자 브랜드 API")
public interface BrandAdminV1ApiSpec {

    ApiResponse<PageResponse<BrandAdminV1Dto.AdminBrandResponse>> getBrands(Integer page, Integer size, String sort);

    ResponseEntity<ApiResponse<BrandAdminV1Dto.AdminBrandResponse>> create(BrandAdminV1Dto.BrandSaveRequest request);

    ApiResponse<BrandAdminV1Dto.AdminBrandResponse> getBrand(Long brandId);

    ApiResponse<BrandAdminV1Dto.AdminBrandResponse> update(Long brandId, BrandAdminV1Dto.BrandSaveRequest request);

    ApiResponse<Object> delete(Long brandId);
}
