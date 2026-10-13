package com.loopers.domain.product;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    ProductModel save(ProductModel product);

    Optional<ProductModel> find(Long id);

    List<ProductModel> findByIds(Collection<Long> ids);

    /** FR-ORDER-02: 비관적 쓰기 락(SELECT ... FOR UPDATE), id 오름차순으로 잠근다 (DR-34). */
    List<ProductModel> findByIdsForUpdate(Collection<Long> ids);

    /** FR-ADMIN-BRAND-05: 삭제되지 않은 소속 상품 전부 (재고 0 포함). */
    List<ProductModel> findActiveByBrandId(Long brandId);

    /** TB-09 latest 첫 페이지 목록의 revision + 1 (DR-37). 대상 행이 없으면 IllegalStateException. */
    void increaseListRevision();
}
