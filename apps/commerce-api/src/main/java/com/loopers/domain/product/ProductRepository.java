package com.loopers.domain.product;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    ProductModel save(ProductModel product);

    Optional<Long> findBrandId(Long productId);

    Optional<ProductModel> find(Long id);

    Optional<ProductModel> findForUpdate(Long id);

    List<ProductModel> findAllActive();

    List<ProductModel> findActiveByPriceAsc(Long brandId, int page, int size);

    List<ProductModel> findActiveByBrandId(Long brandId);

    /** 활성 브랜드의 활성 상품 ID를 좋아요 수·생성 시각·ID 내림차순으로 한 페이지만 고른다. */
    List<Long> findVisibleIdsByLikesDesc(Long brandId, int page, int size);

    List<ProductModel> findActiveByIds(Collection<Long> ids);

    List<Long> findActiveIds(Long brandId, Collection<Long> ids);

    List<ProductModel> findActiveByBrandIdForUpdate(Long brandId);
}
