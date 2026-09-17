package com.loopers.interfaces.api.catalog;

import com.loopers.application.catalog.BrandInfo;

public class BrandAdminV1Dto {
    /** EP-15/17 요청. name 길이 1~100 (검증은 Model, ER-17). */
    public record BrandRequest(String name) {
    }

    /** 설계 4-3-0 BrandAdmin (deleted 포함, ASM-15). */
    public record BrandAdminResponse(Long id, String name, boolean deleted) {
        public static BrandAdminResponse from(BrandInfo info) {
            return new BrandAdminResponse(info.id(), info.name(), info.deleted());
        }
    }
}
