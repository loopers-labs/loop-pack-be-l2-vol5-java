package com.loopers.interfaces.api.brand;

import com.loopers.application.brand.BrandInfo;

import java.time.ZonedDateTime;
import java.util.List;

public class BrandAdminV1Dto {
    public record CreateRequest(String name, String description, String category) {}

    public record UpdateRequest(String name, String description, String category) {}

    public record BrandResponse(
        Long id,
        String name,
        String description,
        String category,
        ZonedDateTime createdAt,
        ZonedDateTime updatedAt,
        ZonedDateTime deletedAt
    ) {
        public static BrandResponse from(BrandInfo info) {
            return new BrandResponse(
                info.id(),
                info.name(),
                info.description(),
                info.category(),
                info.createdAt(),
                info.updatedAt(),
                info.deletedAt()
            );
        }
    }

    public record BrandListResponse(List<BrandResponse> brands) {
        public static BrandListResponse from(List<BrandInfo> infos) {
            return new BrandListResponse(infos.stream().map(BrandResponse::from).toList());
        }
    }
}
