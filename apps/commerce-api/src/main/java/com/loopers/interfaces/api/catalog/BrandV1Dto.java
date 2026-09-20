package com.loopers.interfaces.api.catalog;

import com.loopers.application.catalog.BrandInfo;

public class BrandV1Dto {
    /** 설계 4-3-0 BrandSummary (고객). */
    public record BrandResponse(Long id, String name) {
        public static BrandResponse from(BrandInfo info) {
            return new BrandResponse(info.id(), info.name());
        }
    }
}
