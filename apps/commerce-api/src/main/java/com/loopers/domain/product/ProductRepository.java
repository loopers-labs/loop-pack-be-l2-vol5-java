package com.loopers.domain.product;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    Product save(Product product);

    Optional<Product> findById(Long id);

    List<Product> findAllByIdIn(Collection<Long> ids);

    /**
     * 삭제되지 않은 상품만 조회한다.
     * 정렬 기준이 동률이면 식별자 역순으로 순서를 고정한다.
     *
     * @param brandId 브랜드 필터. null 이면 전체 브랜드를 조회한다.
     */
    List<Product> findAll(Long brandId, ProductSortType sortType, int page, int size);

    /**
     * 관리자 조회용. 삭제된 상품도 포함한다.
     */
    List<Product> findAllForAdmin(Long brandId, int page, int size);

    /**
     * 브랜드에 삭제되지 않은 상품이 남아 있는지 확인한다. 재고 0인 상품도 포함한다.
     */
    boolean existsActiveByBrandId(Long brandId);
}
