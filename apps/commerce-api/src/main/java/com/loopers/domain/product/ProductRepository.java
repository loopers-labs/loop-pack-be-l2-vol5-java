package com.loopers.domain.product;

import com.loopers.domain.common.PageCondition;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    Optional<Product> findActive(Long id);

    List<ProductWithLikeCount> search(ProductSearchCondition condition);

    long count(ProductSearchCondition condition);

    List<Product> findActiveLatest(Long brandId, PageCondition page);

    long countActive(Long brandId);

    List<Product> findActiveLikedBy(Long userId, PageCondition page);

    long countActiveLikedBy(Long userId);

    List<Product> findAllActive(Collection<Long> ids);

    List<Product> findAll(Collection<Long> ids);

    boolean existsActiveByBrandId(Long brandId);

    Product save(Product product);
}
