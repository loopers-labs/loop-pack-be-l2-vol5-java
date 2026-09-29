package com.loopers.interfaces.api.brand;

import com.loopers.domain.brand.BrandDescription;
import com.loopers.domain.brand.BrandName;

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
}
