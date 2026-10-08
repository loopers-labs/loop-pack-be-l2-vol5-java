package com.loopers.domain.product;

import java.util.Optional;
import java.util.List;
import java.time.ZonedDateTime;

public interface ProductRepository {

    Optional<Product> findById(Long productId);

    List<Product> findAll();

    List<Product> findAllActive();

    List<Product> findAllDeleted();

    int updateActiveDetails(Long productId, String name, long price, ZonedDateTime updatedAt);

    int updateActiveStock(Long productId, long stock, ZonedDateTime updatedAt);

    int decreaseActiveStock(Long productId, int quantity, ZonedDateTime updatedAt);

    int softDeleteActiveById(Long productId, ZonedDateTime deletedAt);

    int softDeleteActiveByBrandId(Long brandId, ZonedDateTime deletedAt);

    Product save(Product product);
}
