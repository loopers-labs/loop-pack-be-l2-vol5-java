package com.loopers.application.brand;

import com.loopers.application.product.ProductListCacheInvalidator;
import com.loopers.application.time.ApplicationTime;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrandFacadeTimeTest {

    private static final Long BRAND_ID = 42L;
    private static final ZonedDateTime DELETED_AT = ZonedDateTime.parse("2026-01-01T00:00:00.123456Z");

    @Mock
    private BrandRepository brandRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductListCacheInvalidator productListCacheInvalidator;

    @DisplayName("브랜드를 삭제할 때, Brand와 연결 Product에 같은 삭제 시각을 쓴다.")
    @Test
    void usesOneClockTimeForBrandAndProductDeletion() {
        Brand brand = Brand.create("Nike");
        when(brandRepository.findActiveByIdWithExclusiveLock(BRAND_ID)).thenReturn(Optional.of(brand));
        when(brandRepository.softDeleteActiveById(BRAND_ID, DELETED_AT)).thenReturn(1);

        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00.123456789Z"), ZoneOffset.UTC);
        BrandFacade facade = new BrandFacade(
            brandRepository,
            productRepository,
            productListCacheInvalidator,
            new ApplicationTime(clock)
        );

        facade.delete(BRAND_ID);

        assertThat(brand.getDeletedAt()).isEqualTo(DELETED_AT);
        verify(productRepository).softDeleteActiveByBrandId(BRAND_ID, DELETED_AT);
        verify(brandRepository).softDeleteActiveById(BRAND_ID, DELETED_AT);
    }
}
