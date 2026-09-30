package com.loopers.brand.application.port.in;

/**
 * 브랜드를 바꾸는 유스케이스의 입구(입력 포트). 웹 어댑터는 구현 클래스가 아니라 이 인터페이스를 부른다.
 * 조회는 규칙이 없어 입력 포트 없이 BrandQueryService를 직접 부른다 (CQRS-lite).
 */
public interface BrandCommandUseCase {

    BrandInfo create(String name, String description);

    BrandInfo update(Long brandId, String name, String description);

    void delete(Long brandId);
}
