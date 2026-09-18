package com.loopers.domain.product;

import java.time.ZonedDateTime;

/**
 * 상품과 브랜드 정보를 함께 가져오는 조회 결과 타입 (설계 4.5).
 * Product 는 Brand 를 밖으로 내보내지 않으므로(D-36) 응답의 브랜드 정보는 이 타입에서 가져온다.
 */
public record ProductWithBrand(
    Long id,
    String name,
    long price,
    int stock,
    Long brandId,
    String brandName,
    ZonedDateTime createdAt,
    ZonedDateTime updatedAt
) {}
