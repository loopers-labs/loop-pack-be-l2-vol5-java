package com.loopers.domain.brand;

import java.util.List;
import java.util.Optional;

public interface BrandRepository {
    Optional<BrandModel> find(Long id);

    List<BrandModel> findAllActive();

    /**
     * 목록 조회처럼 N개를 처리할 때 N+1을 피하기 위한 일괄 조회.
     * (docs/week2/design.md 3번 섹션 "brandId를 모아 findAllById로 일괄 조회" 참고)
     */
    List<BrandModel> findAllByIds(List<Long> ids);

    BrandModel save(BrandModel brand);
}
