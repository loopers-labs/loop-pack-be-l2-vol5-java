package com.loopers.application.product.port;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductId;
import com.loopers.domain.brand.BrandId;
import java.util.Optional;

public interface ProductRepository {
    Product save(Product product);
    Optional<Product> findById(ProductId id);
    Optional<Product> findByIdForUpdate(ProductId id);
    boolean existsActiveByBrandId(BrandId id);
}
