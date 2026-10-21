package com.loopers.application.product.port;

import com.loopers.domain.brand.BrandId;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ProductRepository {
    List<Product> findPage(int page, int size);

    List<Product> search(Long brandId, int page, int size, String sort);

    List<Product> findAllByIds(Collection<ProductId> ids);

    Product save(Product product);

    Optional<Product> findById(ProductId id);

    Optional<Product> findByIdForUpdate(ProductId id);

    // 브랜드에 연결된 미삭제 상품을 ID 오름차순으로 기본 키 행 잠금한 뒤, 잠근 상태에서도 미삭제인 상품만 반환한다.
    List<Product> findActiveByBrandIdForUpdate(BrandId brandId);

    // 목록·상세 표시용 like_count를 상품 ID로 일괄 조회한다. 좋아요순 정렬과 같은 값을 읽는다.
    Map<ProductId, Long> findLikeCounts(Collection<ProductId> ids);

    // like_count를 바꾸는 유일한 경로. 읽고-더하고-쓰지 않고 DB에서 원자적으로 더한다.
    void adjustLikeCount(ProductId id, long delta);
}
