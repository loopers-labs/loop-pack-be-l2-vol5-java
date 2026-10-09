package com.loopers.domain.brand;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface BrandRepository {
    Brand save(Brand brand);

    Optional<Brand> findActive(Long brandId);

    /** 삭제되지 않은 브랜드를 생성 시각 desc, 식별자 desc 로 조회한다. */
    Page<Brand> findActive(Pageable pageable);

    /** 살아 있는 브랜드를 배타 잠금으로 읽는다. 브랜드를 바꾸는 수정 · 삭제가 쓴다 (3주차 설계 4.2) */
    Optional<Brand> findActiveForUpdate(Long brandId);

    /** 살아 있는 브랜드를 공유 잠금으로 읽는다. 브랜드를 바꾸지 않고 기대는 상품 등록이 쓴다 (3주차 설계 4.2) */
    Optional<Brand> findActiveForShare(Long brandId);
}
