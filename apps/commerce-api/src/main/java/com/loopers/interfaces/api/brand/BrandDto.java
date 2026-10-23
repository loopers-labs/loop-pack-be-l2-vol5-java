package com.loopers.interfaces.api.brand;

import com.loopers.application.brand.dto.BrandInfo;

public class BrandDto {
    // 고객에게는 식별과 표시에 필요한 id·이름만 노출한다. 생성·수정 시각은 관리자 응답에만 담는다.
    public record BrandResponse(Long id, String name) {
        public static BrandResponse from(BrandInfo info) {
            return new BrandResponse(info.id(), info.name());
        }
    }
}
