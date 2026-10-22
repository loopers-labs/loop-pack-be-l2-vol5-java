package com.loopers.application.product;

import com.loopers.domain.product.ProductSort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ProductListCacheKeyTest {

    @DisplayName("응답 형식 버전·캐시 버전·브랜드 필터·정렬·페이지·크기로 키를 만든다.")
    @Test
    void buildsKeyFromQueryConditionsAndVersions() {
        ProductListCacheKey key = new ProductListCacheKey(7L, ProductSort.PRICE_ASC, 0, 20, 3L);

        assertThat(key.value()).isEqualTo("product:list:v1:ver=3:brand=7:sort=price_asc:page=0:size=20");
    }

    @DisplayName("브랜드 필터가 없으면 전체 브랜드 키를 만든다.")
    @Test
    void usesAllBrandSegment_whenBrandFilterIsAbsent() {
        ProductListCacheKey key = new ProductListCacheKey(null, ProductSort.LATEST, 0, 20, 0L);

        assertThat(key.value()).isEqualTo("product:list:v1:ver=0:brand=all:sort=latest:page=0:size=20");
    }

    @DisplayName("조회 조건이 하나라도 다르면 다른 키를 만든다.")
    @Test
    void buildsDifferentKeys_whenAnyConditionDiffers() {
        ProductListCacheKey base = new ProductListCacheKey(7L, ProductSort.PRICE_ASC, 0, 20, 3L);

        assertThat(Arrays.asList(
            new ProductListCacheKey(8L, ProductSort.PRICE_ASC, 0, 20, 3L).value(),
            new ProductListCacheKey(null, ProductSort.PRICE_ASC, 0, 20, 3L).value(),
            new ProductListCacheKey(7L, ProductSort.LIKES_DESC, 0, 20, 3L).value(),
            new ProductListCacheKey(7L, ProductSort.PRICE_ASC, 1, 20, 3L).value(),
            new ProductListCacheKey(7L, ProductSort.PRICE_ASC, 0, 10, 3L).value(),
            new ProductListCacheKey(7L, ProductSort.PRICE_ASC, 0, 20, 4L).value()
        )).doesNotContain(base.value()).doesNotHaveDuplicates();
    }

    @DisplayName("캐시에 저장하는 상품 정보는 사용자별 필드 없이 공통 필드만 가진다.")
    @Test
    void cachedPayloadHasOnlyCommonFields() {
        assertThat(CustomerProductInfo.class.getRecordComponents())
            .extracting(RecordComponent::getName)
            .containsExactly("id", "brandId", "brandName", "name", "price", "likeCount");
    }
}
