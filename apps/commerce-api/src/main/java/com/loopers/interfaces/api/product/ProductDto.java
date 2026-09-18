package com.loopers.interfaces.api.product;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.loopers.application.product.ProductInfo;
import com.loopers.application.product.query.ProductView;
import com.loopers.interfaces.api.support.StrictLongDeserializer;
import com.loopers.interfaces.api.support.StrictIntegerDeserializer;

public class ProductDto {
    public record Create(@JsonDeserialize(using = StrictLongDeserializer.class) Long brandId, String name,
                         @JsonDeserialize(using = StrictLongDeserializer.class) Long price) {}
    public record Update(String name, @JsonDeserialize(using = StrictLongDeserializer.class) Long price) {}
    public record Stock(@JsonDeserialize(using = StrictIntegerDeserializer.class) Integer stock) {}
    public record StockResponse(long productId, int stock) {}
    public record Admin(long productId, long brandId, String name, long price, int stock, boolean deleted) {
        public static Admin from(ProductInfo p) { return new Admin(p.productId(), p.brandId(), p.name(), p.price(), p.stock(), p.deleted()); }
    }
    public record Brand(long brandId, String name) {}
    public record Customer(long productId, String name, long price, Brand brand, long likeCount) {
        public static Customer from(ProductView p) {
            return new Customer(p.productId(), p.name(), p.price(), new Brand(p.brand().brandId(), p.brand().name()), p.likeCount());
        }
    }
}
