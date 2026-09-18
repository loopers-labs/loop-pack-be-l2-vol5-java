package com.loopers.interfaces.api.brand;

import com.loopers.application.brand.BrandInfo;

public class BrandV1Dto {

    public record BrandView(Long brandId, String name) {

        public static BrandView from(BrandInfo info) {
            return new BrandView(info.brandId(), info.name());
        }
    }
}
