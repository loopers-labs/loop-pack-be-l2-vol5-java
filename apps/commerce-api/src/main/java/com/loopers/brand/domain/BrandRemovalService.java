package com.loopers.brand.domain;

import com.loopers.product.domain.Product;

import java.util.List;

public class BrandRemovalService {

    public void remove(Brand brand, List<Product> products) {
        brand.delete();
        for (Product product : products) {
            if (!product.isDeleted()) {
                product.delete();
            }
        }
    }
}
