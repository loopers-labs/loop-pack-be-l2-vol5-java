package com.loopers.domain.brand;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface BrandRepository {
    Brand save(Brand brand);

    Optional<Brand> findActive(Long brandId);

    /** 삭제되지 않은 브랜드를 생성 시각 desc, 식별자 desc 로 조회한다. */
    Page<Brand> findActive(Pageable pageable);

    /** 브랜드에 삭제되지 않은 상품(재고 0 포함)이 있는가. 상품 클래스를 알지 않도록 브랜드 쪽 질문으로 둔다 (설계 D-32). */
    boolean hasActiveProduct(Long brandId);
}
