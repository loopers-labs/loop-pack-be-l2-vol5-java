package com.loopers.domain.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    Optional<Product> findById(long id);

    /**
     * 신규 상품을 저장하거나 객체가 가진 전체 상태를 반영한다.
     * 기존 상품의 동시 변경에는 목적별 변경 메서드를 사용한다.
     */
    Product save(Product product);

    Page<Product> findAll(Pageable pageable);

    /** 재고 수량과 관계없이 미삭제 상품의 ID를 오름차순으로 반환한다. */
    List<Long> findActiveIdsByBrandId(long brandId);

    /** 삭제 상태만 변경한다. 대상이 없거나 이미 삭제됐으면 변경하지 않는다. */
    void delete(long id);

    /**
     * 현재 DB 재고에서 양수 수량을 차감한다.
     * 잘못된 수량은 INVALID_REQUEST, 없거나 삭제된 상품은 PRODUCT_NOT_FOUND,
     * 재고 부족은 INSUFFICIENT_STOCK으로 거절한다.
     */
    void deductStock(long id, int quantity);

    /**
     * 재고만 지정한 최종 수량으로 변경하고 저장 결과를 반환한다.
     * 음수 수량은 INVALID_REQUEST, 없거나 삭제된 상품은 PRODUCT_NOT_FOUND로 거절한다.
     * 상품 확인과 입력 검사의 우선순위는 유스케이스에서 결정한다.
     */
    Product setStock(long id, int stock);

    /**
     * 도메인에서 검사한 이름과 가격만 저장하며 최신 재고는 유지한다.
     * 없거나 삭제된 상품은 PRODUCT_NOT_FOUND로 거절한다.
     */
    Product updateInformation(Product product);
}
