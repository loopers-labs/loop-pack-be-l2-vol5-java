package com.loopers.interfaces.api.admin.brand;

import com.loopers.application.brand.BrandInfo;

import java.time.ZonedDateTime;

public class BrandAdminV1Dto {

    public record CreateRequest(String name, String description) {}

    public record UpdateRequest(String name, String description) {}

    /**
     * 관리자용 브랜드 응답. 운영에 필요한 생성·수정 시각을 포함한다 (7-2).
     */
    public record BrandResponse(Long id, String name, String description, ZonedDateTime createdAt, ZonedDateTime updatedAt) {
        public static BrandResponse from(BrandInfo info) {
            return new BrandResponse(info.id(), info.name(), info.description(), info.createdAt(), info.updatedAt());
        }
    }
}
