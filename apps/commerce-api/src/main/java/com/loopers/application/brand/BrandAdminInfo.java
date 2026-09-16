package com.loopers.application.brand;

import com.loopers.domain.brand.BrandModel;

public record BrandAdminInfo(Long id, String name, boolean deleted) {
    public static BrandAdminInfo from(BrandModel model) {
        return new BrandAdminInfo(model.getId(), model.getName(), model.getDeletedAt() != null);
    }
}
