package com.loopers.infrastructure.query.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loopers.application.common.PageCriteria;
import com.loopers.application.common.PageResult;
import com.loopers.application.mall.query.AdminProductView;
import com.loopers.application.mall.query.BrandSummaryView;
import com.loopers.application.mall.query.ProductCriteria;
import com.loopers.application.mall.query.ProductDetailView;
import com.loopers.application.mall.query.ProductQueryDao;
import com.loopers.application.mall.query.ProductSort;
import com.loopers.application.mall.query.ProductSummaryView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CachedProductQueryDaoTest {
    private final ProductQueryDao raw = mock(ProductQueryDao.class);
    private final ProductDetailCache cache = new ProductDetailCache(
        new ProductDetailCacheProperties(true, 1000, Duration.ofSeconds(30)), new SimpleMeterRegistry());
    private final CachedProductQueryDao dao = new CachedProductQueryDao(raw, cache);

    @DisplayName("여러 DAO 구현이 있어도 기본 주입은 데코레이터이며 지정한 원래 DAO에 위임한다")
    @Test
    void wiresPrimaryDecoratorWithQualifiedDelegate() {
        // arrange
        ProductDetailView detail = new ProductDetailView(1, "상품", 1000, new BrandSummaryView(7, "브랜드"), 2, "설명", 3);
        when(raw.findProduct(1)).thenReturn(Optional.of(detail));

        // act, assert
        new ApplicationContextRunner()
            .withUserConfiguration(CacheConfiguration.class, ProductDetailCache.class, CachedProductQueryDao.class)
            .withBean("queryDslProductQueryDao", ProductQueryDao.class, () -> raw)
            .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("query.product-detail-cache.enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                ProductQueryDao primary = context.getBean(ProductQueryDao.class);
                assertThat(primary).isInstanceOf(CachedProductQueryDao.class);
                assertThat(primary.findProduct(1)).contains(detail);
                assertThat(primary.findProduct(1)).contains(detail);
                verify(raw).findProduct(1);
            });
    }

    @DisplayName("고객 상세 조회만 캐시를 거쳐 같은 전체 DTO를 반환한다")
    @Test
    void cachesCustomerDetail() {
        // arrange
        ProductDetailView detail = new ProductDetailView(1, "상품", 1000, new BrandSummaryView(7, "브랜드"), 2, "설명", 3);
        when(raw.findProduct(1)).thenReturn(Optional.of(detail));

        // act, assert
        assertThat(dao.findProduct(1)).contains(detail);
        assertThat(dao.findProduct(1)).contains(detail);
        verify(raw).findProduct(1);
    }

    @DisplayName("고객·관리자 목록과 관리자 상세는 매번 원래 DAO에 위임한다")
    @Test
    void delegatesOtherQueriesWithoutCaching() {
        // arrange
        ProductCriteria criteria = new ProductCriteria(null, ProductSort.LATEST, new PageCriteria(0, 20));
        PageResult<ProductSummaryView> products = PageResult.of(List.of(), 0, 20, 0);
        PageResult<AdminProductView> admins = PageResult.of(List.of(), 0, 20, 0);
        AdminProductView detail = new AdminProductView(1, "상품", 1000, new BrandSummaryView(7, "브랜드"), 2,
            "설명", 3, Instant.EPOCH);
        when(raw.findProducts(criteria)).thenReturn(products);
        when(raw.findAdminProducts(criteria)).thenReturn(admins);
        when(raw.findAdminProduct(1)).thenReturn(Optional.of(detail));

        // act, assert
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThat(dao.findProducts(criteria)).isSameAs(products);
            assertThat(dao.findAdminProducts(criteria)).isSameAs(admins);
            assertThat(dao.findAdminProduct(1)).contains(detail);
        }
        verify(raw, times(2)).findProducts(criteria);
        verify(raw, times(2)).findAdminProducts(criteria);
        verify(raw, times(2)).findAdminProduct(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProductDetailCacheProperties.class)
    static class CacheConfiguration {}
}
