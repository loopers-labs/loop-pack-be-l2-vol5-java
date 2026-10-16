package com.loopers.application.product;

import java.util.List;

/**
 * product-perf-seed.sql 의 공식으로 계산한 "브랜드 7 + price_asc + page 0 + size 20" 의 기대 결과.
 * 캐시 없는 설정과 캐시 있는 설정은 서로 다른 Spring 컨텍스트라 한 테스트에서 직접 비교할 수 없으므로,
 * 양쪽 모두 이 고정값과 같은지 확인하는 방식으로 DB-only / miss / hit 결과를 비교한다.
 *
 * 공식: 상품 i 의 brand_id = (i % 100) + 1, amount = ((i * 37) % 100000) + 100, quantity = 100,
 *       좋아요 수 = 500 (i % 999 = 0) / 0 (i % 11 = 0) / ((i * 13) % 47) + 1 (그 외). 정렬은 amount ASC, id DESC.
 * docs/week4/perf/step3/A-response.json 의 실측 응답과 같은 값이다.
 */
final class ProductQueryFixture {

    static final Long BRAND_ID = 7L;
    static final String SORT = "price_asc";
    static final int PAGE = 0;
    static final int SIZE = 20;
    static final String CACHE_KEY = "products:v1:brand=7:sort=price_asc:page=0:size=20";

    static final List<ProductInfo> EXPECTED_PAGE_0 = List.of(
        new ProductInfo(5406L, "product-5406", 122L, 100, 7L, "brand-7", 14L),
        new ProductInfo(2706L, "product-2706", 222L, 100, 7L, "brand-7", 0L),
        new ProductInfo(6L, "product-6", 322L, 100, 7L, "brand-7", 32L),
        new ProductInfo(8206L, "product-8206", 3722L, 100, 7L, "brand-7", 0L),
        new ProductInfo(5506L, "product-5506", 3822L, 100, 7L, "brand-7", 45L),
        new ProductInfo(2806L, "product-2806", 3922L, 100, 7L, "brand-7", 7L),
        new ProductInfo(106L, "product-106", 4022L, 100, 7L, "brand-7", 16L),
        new ProductInfo(8306L, "product-8306", 7422L, 100, 7L, "brand-7", 20L),
        new ProductInfo(5606L, "product-5606", 7522L, 100, 7L, "brand-7", 29L),
        new ProductInfo(2906L, "product-2906", 7622L, 100, 7L, "brand-7", 38L),
        new ProductInfo(206L, "product-206", 7722L, 100, 7L, "brand-7", 47L),
        new ProductInfo(8406L, "product-8406", 11122L, 100, 7L, "brand-7", 4L),
        new ProductInfo(5706L, "product-5706", 11222L, 100, 7L, "brand-7", 13L),
        new ProductInfo(3006L, "product-3006", 11322L, 100, 7L, "brand-7", 22L),
        new ProductInfo(306L, "product-306", 11422L, 100, 7L, "brand-7", 31L),
        new ProductInfo(8506L, "product-8506", 14822L, 100, 7L, "brand-7", 35L),
        new ProductInfo(5806L, "product-5806", 14922L, 100, 7L, "brand-7", 44L),
        new ProductInfo(3106L, "product-3106", 15022L, 100, 7L, "brand-7", 6L),
        new ProductInfo(406L, "product-406", 15122L, 100, 7L, "brand-7", 15L),
        new ProductInfo(8606L, "product-8606", 18522L, 100, 7L, "brand-7", 19L)
    );

    /**
     * 첫 페이지 바로 다음(21번째) 상품. 첫 페이지의 상품 하나가 삭제되면 이 상품이 20번째로 올라온다.
     */
    static final ProductInfo EXPECTED_21ST = new ProductInfo(5906L, "product-5906", 18622L, 100, 7L, "brand-7", 28L);

    private ProductQueryFixture() {}
}
