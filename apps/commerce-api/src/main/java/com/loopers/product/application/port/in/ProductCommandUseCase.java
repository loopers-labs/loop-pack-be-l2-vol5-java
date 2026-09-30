package com.loopers.product.application.port.in;

/**
 * 관리자가 상품을 바꾸는 유스케이스의 입구(입력 포트). 조회는 ProductAdminQueryService가 맡는다.
 */
public interface ProductCommandUseCase {

    ProductAdminInfo create(Long brandId, String name, long price, int stock);

    ProductAdminInfo update(Long productId, String name, long price);

    ProductAdminInfo changeStock(Long productId, int stock);

    void delete(Long productId);
}
