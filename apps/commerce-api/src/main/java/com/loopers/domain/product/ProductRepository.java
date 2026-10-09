package com.loopers.domain.product;

import java.util.Optional;
import java.time.ZonedDateTime;

public interface ProductRepository {

    Product save(Product product);

    Optional<Product> findById(long productId);

    Optional<Product> lockById(long productId);

    Optional<Long> findBrandId(long productId);

    /**
     * 호출자가 브랜드 쓰기 잠금을 보유한 READ_COMMITTED 트랜잭션에서 연결된 미삭제 상품을 논리 삭제한다.
     * 상품 ID 오름차순으로 잠금을 얻고, 기존 삭제 상품과 다른 브랜드는 변경하지 않는다.
     */
    int softDeleteNonDeletedByBrandId(long brandId, ZonedDateTime deletedAt);

    /**
     * 해당 브랜드에 속한 미삭제 상품이 하나라도 있는지 조회한다.
     * 재고가 0인 상품도 포함하고, 삭제된 상품은 제외한다.
     */
    boolean existsNonDeletedByBrandId(long brandId);
}
