package com.loopers.interfaces.api.brand;

import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageSize;
import com.loopers.domain.common.PageWindow;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandDescription;
import com.loopers.domain.brand.BrandName;

import java.util.List;

public class BrandAdminV1Dto {

    public record RegisterRequest(String name, String description) {
        public RegisterRequest {
            BrandName.of(name);
            BrandDescription.of(description);
        }
    }

    public record UpdateRequest(String name, String description) {
        public UpdateRequest {
            BrandName.of(name);
            BrandDescription.of(description);
        }
    }

    public record AdminBrandPageResponse(
        List<BrandV1Dto.BrandResponse> items, int page, int size, boolean hasNext) {
        public static AdminBrandPageResponse of(List<Brand> rows, PageNumber page, PageSize size) {
            PageWindow<Brand> window = PageWindow.of(rows, size);
            return new AdminBrandPageResponse(
                window.items().stream().map(BrandV1Dto.BrandResponse::from).toList(),
                page.value(), size.value(), window.hasNext());
        }
    }
}
