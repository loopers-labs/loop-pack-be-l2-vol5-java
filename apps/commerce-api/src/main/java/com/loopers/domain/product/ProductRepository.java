package com.loopers.domain.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    ProductModel save(ProductModel product);

    Optional<ProductModel> findActiveById(Long id);

    Optional<ProductModel> findById(Long id);

    // 삭제된 상품도 조회한다. 삭제 여부는 잠근 뒤에 확인한다
    Optional<ProductModel> findByIdForUpdate(Long id);

    Page<ProductModel> findAll(Pageable pageable);

    Page<ProductModel> findAllActive(Pageable pageable);

    Page<ProductModel> findAllActiveByBrandId(Long brandId, Pageable pageable);

    // 취소되지 않은 좋아요 수 내림차순, 같으면 id 오름차순. 전체 후보를 정렬한 뒤 페이지를 자른다
    Page<ProductModel> findAllActiveOrderByLikeCountDesc(Pageable pageable);

    Page<ProductModel> findAllActiveByBrandIdOrderByLikeCountDesc(Long brandId, Pageable pageable);

    // id 오름차순. 엔티티를 읽으면 잠금 없이 로드되므로 id만 돌려준다
    List<Long> findActiveIdsByBrandId(Long brandId);

    List<ProductModel> findAllActiveByIds(List<Long> ids);

    boolean existsActiveByBrandId(Long brandId);
}
