package com.loopers.interfaces.api.brand;

import com.loopers.application.brand.BrandInfo;

public class BrandDto {
    public record Request(String name) {}
    public record Customer(long brandId, String name) {
        public static Customer from(BrandInfo info) { return new Customer(info.brandId(), info.name()); }
    }
    public record Admin(long brandId, String name, boolean deleted) {
        public static Admin from(BrandInfo info) { return new Admin(info.brandId(), info.name(), info.deleted()); }
    }
}
