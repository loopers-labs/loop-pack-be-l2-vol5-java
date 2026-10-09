package com.loopers.domain.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ProductRepository {
    Product save(Product product);

    Optional<Product> findActive(Long productId);

    /** 살아 있는 상품을 배타 잠금으로 읽는다. product 행을 쓰는 경로가 쓴다 (3주차 설계 4.2) */
    Optional<Product> findActiveForUpdate(Long productId);

    /**
     * 주어진 식별자 중 살아 있는 상품을 식별자 오름차순으로 한 번에 배타 잠금한다. 없거나 삭제된 상품은 빠진다.
     * 여러 상품을 잠그는 경로가 같은 순서로 잠가 교착을 피한다 (3주차 설계 4.3)
     */
    List<Product> findAllActiveForUpdate(Collection<Long> productIds);

    /** 주어진 식별자 중 삭제되지 않은 상품의 식별자. 없거나 삭제된 상품은 빠짐 */
    Set<Long> findActiveIds(Collection<Long> productIds);

    Optional<ProductWithBrand> findActiveWithBrand(Long productId);

    /** 삭제되지 않은 상품을 생성 시각 desc, 식별자 desc 로 조회한다. brandId 가 null 이면 브랜드로 거르지 않는다. */
    Page<ProductWithBrand> findActiveWithBrand(Long brandId, Pageable pageable);

    Optional<ProductView> findActiveView(Long productId);

    /**
     * 삭제되지 않은 상품을 좋아요 수와 함께 조회한다. 좋아요가 없는 상품도 포함한다.
     * brandId 가 null 이면 브랜드로 거르지 않고, 없는 브랜드면 빈 결과다.
     */
    Page<ProductView> findActiveViews(Long brandId, ProductSort sort, Pageable pageable);

    /**
     * 브랜드의 삭제되지 않은 상품(재고 0 포함)을 한 문장으로 삭제하고 삭제한 행 수를 돌려준다.
     * 엔티티를 거치지 않으므로 삭제 시각과 수정 시각을 직접 받는다 (3주차 설계 2.3).
     */
    int deleteAllOfBrand(Long brandId, ZonedDateTime deletedAt);
}
