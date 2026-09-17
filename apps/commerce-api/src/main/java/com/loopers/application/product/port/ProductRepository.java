package com.loopers.application.product.port;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductId;
import com.loopers.domain.brand.BrandId;
import java.util.Optional;

public interface ProductRepository {
    java.util.List<Product> findPage(int page, int size);
    java.util.List<Product> search(Long brandId, int page, int size, String sort);
    java.util.List<Product> findAllByIds(java.util.Collection<ProductId> ids);
    Product save(Product product);
    Optional<Product> findById(ProductId id);
    Optional<Product> findByIdForUpdate(ProductId id);
    boolean existsActiveByBrandId(BrandId id);
}
