package com.loopers.domain.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    Optional<ProductModel> find(Long id);

    /**
     * 비관적 락(SELECT ... FOR UPDATE)으로 조회한다 — 주문 확정 시 재고 차감 전에만 쓴다.
     * (docs/week2/design.md 5번 섹션 "차감 → 비관적 락" 참고)
     */
    Optional<ProductModel> findForUpdate(Long id);

    List<ProductModel> findAllActive();

    /**
     * 목록 조회처럼 N개를 처리할 때 N+1을 피하기 위한 일괄 조회. 락을 걸지 않는다.
     * (docs/week2/design.md 3번 섹션 "brandId를 모아 findAllById로 일괄 조회" 참고)
     */
    List<ProductModel> findAllByIds(List<Long> ids);

    boolean existsActiveByBrandId(Long brandId);

    Page<ProductModel> findActive(Long brandId, ProductSort sort, Pageable pageable);

    ProductModel save(ProductModel product);
}
