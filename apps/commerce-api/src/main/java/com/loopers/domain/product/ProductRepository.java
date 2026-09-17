package com.loopers.domain.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 상품 상태를 바꾸는 쪽의 저장 약속. 조회 화면용 조합은 ProductQueryRepository가 맡는다 (ADR-02).
 */
public interface ProductRepository {

    ProductModel save(ProductModel product);

    /**
     * PRD-06: 삭제되지 않은 상품만 찾는다.
     */
    Optional<ProductModel> findActiveById(Long id);

    /**
     * 삭제되지 않은 상품 목록. brandId가 null이면 전체.
     */
    Page<ProductModel> findActive(Long brandId, Pageable pageable);

    /**
     * BRD-02: 브랜드에 삭제되지 않은 상품이 남아 있는가 (재고 0 포함).
     */
    boolean existsActiveByBrandId(Long brandId);

    /**
     * 삭제 여부와 관계없이 식별자로 한 번에 찾는다. 팔 수 있는지는 호출자가 상품에 묻는다 (PRD-06).
     */
    List<ProductModel> findAllByIds(Collection<Long> ids);
}
