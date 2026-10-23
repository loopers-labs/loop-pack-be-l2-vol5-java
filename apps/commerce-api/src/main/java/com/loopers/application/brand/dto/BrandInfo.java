package com.loopers.application.brand.dto;

import com.loopers.domain.brand.Brand;

import java.time.ZonedDateTime;

/**
 * 브랜드 조회 결과. 고객·관리자가 함께 쓰며, 어떤 필드를 응답에 담을지는 각 Dto 가 고른다.
 */
public record BrandInfo(Long id, String name, ZonedDateTime createdAt, ZonedDateTime updatedAt) {
    public static BrandInfo from(Brand brand) {
        return new BrandInfo(brand.getId(), brand.getName(), brand.getCreatedAt(), brand.getUpdatedAt());
    }
}
