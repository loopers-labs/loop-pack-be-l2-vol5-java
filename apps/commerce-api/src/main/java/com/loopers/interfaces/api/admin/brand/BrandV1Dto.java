package com.loopers.interfaces.api.admin.brand;

import com.loopers.application.brand.BrandInfo;

public class BrandV1Dto {

    public record CreateRequest(String name) {}

    public record BrandResponse(
        Long id,
        String name,
        boolean deleted
    ) {
        public static BrandResponse from(BrandInfo info) {
            return new BrandResponse(info.id(), info.name(), info.deleted());
        }
    }
}
