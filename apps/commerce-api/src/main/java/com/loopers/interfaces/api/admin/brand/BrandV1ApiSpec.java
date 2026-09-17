package com.loopers.interfaces.api.admin.brand;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Admin Brand V1 API", description = "관리자 브랜드 API입니다.")
public interface BrandV1ApiSpec {

    @Operation(summary = "브랜드 상세 조회", description = "브랜드 상세 정보를 조회합니다.")
    ApiResponse<BrandV1Dto.BrandResponse> getDetail(Long brandId);

    @Operation(summary = "브랜드 수정", description = "브랜드 이름을 수정합니다.")
    ApiResponse<BrandV1Dto.BrandResponse> update(Long brandId, BrandV1Dto.UpdateRequest request);

    @Operation(summary = "브랜드 등록", description = "브랜드를 등록합니다.")
    ApiResponse<BrandV1Dto.BrandResponse> register(BrandV1Dto.CreateRequest request);
}
