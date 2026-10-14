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
     * 브랜드의 삭제되지 않은 상품을 식별자 오름차순으로 전부 조회한다. 재고 0인 상품도 포함한다.
     */
    List<Product> findAllActiveByBrandId(Long brandId);

    /**
     * 삭제되지 않았고 재고가 충분할 때만 quantity = quantity - quantity 로 차감한다.
     * Stock 이 메모리에서 하던 "재고 부족 → 거절" 상태 검증과 차감을 WHERE 조건부 UPDATE 로 대신한다.
     *
     * @return 갱신된 행 수. 0 이면 재고 부족이거나 없거나 삭제된 상품이다.
     */
    int deductStockIfEnough(Long productId, int quantity);

    /**
     * 삭제되지 않은 상품의 재고를 최종 수량으로 설정한다.
     * Stock.changeQuantity 가 메모리에서 하던 값 변경을 단일 UPDATE 로 대신한다.
     *
     * @return 갱신된 행 수. 0 이면 없거나 삭제된 상품이다.
     */
    int updateStock(Long productId, int quantity);
}
