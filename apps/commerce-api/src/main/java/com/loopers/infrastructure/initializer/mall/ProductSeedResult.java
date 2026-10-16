package com.loopers.infrastructure.initializer.mall;

// 실험 seed의 검증된 건수와 합계. 운영 조회 응답에는 사용하지 않는다.
public record ProductSeedResult(long brands, long users, long wallets, long products,
                                long activeProducts, long deletedProducts, long priceSum, long likes,
                                long zeroLikeProducts, long popularProducts, long brand7ActiveProducts,
                                long brand7ActivePriceSum) {
    static ProductSeedResult benchmark() {
        return new ProductSeedResult(1000, 1000, 1000, 1_000_000, 950_400, 49_600,
            50_950_000_000L, 2_597_980, 166_338, 1000, 960, 49_004_000);
    }
}
