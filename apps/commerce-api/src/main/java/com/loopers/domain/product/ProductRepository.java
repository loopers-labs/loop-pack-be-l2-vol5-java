package com.loopers.domain.product;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface ProductRepository {

    Optional<Product> findById(Long productId);

    List<Product> findAll();

    List<Product> findAllActive();

    List<Product> findActivePage(Long brandId, ProductSort sort, int page, int size);

    long countActiveByIds(List<Long> productIds);

    List<Product> findAllDeleted();

    int updateActiveDetails(Long productId, String name, long price, ZonedDateTime updatedAt);

    int updateActiveStock(Long productId, long stock, ZonedDateTime updatedAt);

    int decreaseActiveStock(Long productId, int quantity, ZonedDateTime updatedAt);

    int softDeleteActiveById(Long productId, ZonedDateTime deletedAt);

    int softDeleteActiveByBrandId(Long brandId, ZonedDateTime deletedAt);

    Product save(Product product);
}
