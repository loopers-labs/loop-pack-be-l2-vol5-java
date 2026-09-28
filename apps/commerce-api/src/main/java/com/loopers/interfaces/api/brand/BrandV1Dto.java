package com.loopers.interfaces.api.brand;

import com.loopers.application.brand.BrandInfo;
import com.loopers.application.brand.query.BrandView;

public class BrandV1Dto {
    /** 설계 4-3-0 BrandSummary (고객). */
    public record BrandResponse(Long id, String name) {
        public static BrandResponse from(BrandInfo info) {
            return new BrandResponse(info.id(), info.name());
        }

        public static BrandResponse from(BrandView.Summary view) {
            return new BrandResponse(view.id(), view.name());
        }
    }
}
