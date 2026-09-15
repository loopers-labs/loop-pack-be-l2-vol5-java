package com.loopers.interfaces.api.brand;

import com.loopers.application.brand.BrandInfo;

public class BrandV1Dto {
    /**
     * 고객용 브랜드 응답. 생성·수정 시각 같은 운영 정보는 넣지 않는다 (7-2).
     */
    public record BrandResponse(Long id, String name, String description) {
        public static BrandResponse from(BrandInfo info) {
            return new BrandResponse(info.id(), info.name(), info.description());
        }
    }
}
