package com.loopers.domain.product;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    ProductModel save(ProductModel product);

    Optional<ProductModel> find(Long id);

    List<ProductModel> findByIds(Collection<Long> ids);

    /** FR-ORDER-02: 비관적 읽기 락(SELECT ... FOR SHARE), id 오름차순으로 잠근다. 확정 중 상품 수정·삭제를 막는다 (DR-38). */
    List<ProductModel> findByIdsForShare(Collection<Long> ids);

    /** FR-ADMIN-BRAND-05: 삭제되지 않은 소속 상품 전부 (재고 0 포함). */
    List<ProductModel> findActiveByBrandId(Long brandId);

    /**
     * TB-03 옛 stock 컬럼을 현재 재고 값으로 맞춘다 (DR-38, 컬럼 삭제 때 제거). 상품 엔티티를 거치지 않는 단건 갱신이라 다른 컬럼을 덮지 않는다.
     * 대상 행이 없으면 IllegalStateException.
     */
    void syncLegacyStock(Long productId, int stock);

    ProductStockModel saveStock(ProductStockModel stock);

    Optional<ProductStockModel> findStock(Long productId);

    List<ProductStockModel> findStocks(Collection<Long> productIds);

    /** FR-ORDER-02, FR-ADMIN-PRODUCT-06: 비관적 쓰기 락(SELECT ... FOR UPDATE), product_id 오름차순으로 잠근다 (DR-34, DR-38). */
    List<ProductStockModel> findStocksForUpdate(Collection<Long> productIds);

    ProductStockLedgerModel saveLedger(ProductStockLedgerModel entry);

    /** TB-09 latest 첫 페이지 목록의 revision + 1 (DR-37). 대상 행이 없으면 IllegalStateException. */
    void increaseListRevision();
}
