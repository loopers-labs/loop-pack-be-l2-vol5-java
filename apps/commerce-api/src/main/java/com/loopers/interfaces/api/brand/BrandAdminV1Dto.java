package com.loopers.interfaces.api.brand;

import com.loopers.application.brand.BrandInfo;
import com.loopers.application.brand.query.BrandView;

public class BrandAdminV1Dto {
    /** EP-15/17 요청. name 길이 1~100 (검증은 Model, ER-17). */
    public record BrandRequest(String name) {
    }

    /** 설계 4-3-0 BrandAdmin (deleted 포함, ASM-15). */
    public record BrandAdminResponse(Long id, String name, boolean deleted) {
        public static BrandAdminResponse from(BrandInfo info) {
            return new BrandAdminResponse(info.id(), info.name(), info.deleted());
        }

        public static BrandAdminResponse from(BrandView.Admin view) {
            return new BrandAdminResponse(view.id(), view.name(), view.deleted());
        }
    }
}
