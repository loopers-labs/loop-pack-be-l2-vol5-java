package com.loopers.application.product;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductListCacheWarmerTest {

    @Mock
    private ProductFacade productFacade;

    @Mock
    private BrandRepository brandRepository;

    @InjectMocks
    private ProductListCacheWarmer warmer;

    @Test
    @DisplayName("정렬마다 전체 목록과 활성 브랜드 목록의 첫 페이지를 기본 크기로 채운다.")
    void warmsFirstPageOfEverySortForAllAndActiveBrands() {
        when(brandRepository.findAllActive()).thenReturn(List.of(brand(1L), brand(2L)));

        ProductListCacheWarmer.Result result = warmer.warmUp();

        for (String sort : List.of("latest", "price_asc", "likes_desc")) {
            verify(productFacade).getCustomerList(null, 0, 20, sort);
            verify(productFacade).getCustomerList(1L, 0, 20, sort);
            verify(productFacade).getCustomerList(2L, 0, 20, sort);
        }
        assertThat(result.warmed()).isEqualTo(9);
        assertThat(result.failed()).isZero();
    }

    @Test
    @DisplayName("한 조건의 채우기가 실패해도 나머지 조건을 계속 채운다.")
    void continuesWhenOneConditionFails() {
        when(brandRepository.findAllActive()).thenReturn(List.of(brand(1L)));
        // 다른 조건으로도 호출되는 것이 의도이므로 이 스텁만 엄격한 인자 검사에서 제외한다.
        lenient().doThrow(new IllegalStateException("DB 오류"))
            .when(productFacade).getCustomerList(eq(1L), anyInt(), anyInt(), eq("likes_desc"));

        ProductListCacheWarmer.Result result = warmer.warmUp();

        verify(productFacade, times(6)).getCustomerList(any(), anyInt(), anyInt(), anyString());
        verify(productFacade).getCustomerList(isNull(), eq(0), eq(20), eq("likes_desc"));
        assertThat(result.warmed()).isEqualTo(5);
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("활성 브랜드 목록을 읽지 못하면 실패로 집계하고 예외를 던지지 않는다.")
    void reportsFailure_whenActiveBrandsCannotBeRead() {
        when(brandRepository.findAllActive()).thenThrow(new IllegalStateException("DB 오류"));

        ProductListCacheWarmer.Result result = warmer.warmUp();

        assertThat(result.warmed()).isZero();
        assertThat(result.failed()).isEqualTo(1);
    }

    private Brand brand(Long id) {
        return Brand.reconstitute(id, "brand-" + id, ZonedDateTime.now(), null);
    }
}
