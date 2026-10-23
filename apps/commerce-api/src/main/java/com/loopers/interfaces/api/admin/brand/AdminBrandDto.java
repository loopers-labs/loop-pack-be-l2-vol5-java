package com.loopers.interfaces.api.admin.brand;

import com.loopers.application.brand.dto.BrandInfo;
import jakarta.validation.constraints.NotNull;

import java.time.ZonedDateTime;

public class AdminBrandDto {
    // 필수 누락(@NotNull)은 요청 형식 오류라 여기서 거절한다. 공백·길이는 Brand 가 판단한다. 등록과 수정이 같은 요청 형식을 쓴다.
    public record BrandRequest(@NotNull String name) {}

    public record BrandResponse(Long id, String name, ZonedDateTime createdAt, ZonedDateTime updatedAt) {
        public static BrandResponse from(BrandInfo info) {
            return new BrandResponse(info.id(), info.name(), info.createdAt(), info.updatedAt());
        }
    }
}
