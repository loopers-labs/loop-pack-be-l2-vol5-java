package com.loopers.application.product;

import com.loopers.domain.product.ProductSort;

import java.util.Locale;

public record ProductListCacheKey(Long brandId, ProductSort sort, int page, int size, long version) {

    // 응답 필드가 바뀌면 형식 버전을 올려 이전 형식의 캐시를 읽지 않는다.
    private static final String FORMAT_VERSION = "v1";

    public String value() {
        return "product:list:" + FORMAT_VERSION
            + ":ver=" + version
            + ":brand=" + (brandId == null ? "all" : brandId)
            + ":sort=" + sort.name().toLowerCase(Locale.ROOT)
            + ":page=" + page
            + ":size=" + size;
    }
}
