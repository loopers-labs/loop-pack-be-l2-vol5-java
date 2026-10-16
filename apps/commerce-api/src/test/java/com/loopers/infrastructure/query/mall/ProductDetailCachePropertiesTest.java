package com.loopers.infrastructure.query.mall;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProductDetailCachePropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class);

    @DisplayName("설정을 생략하면 상세 캐시는 꺼지고 용량 1000과 TTL 30초를 사용한다")
    @Test
    void bindsDefaults() {
        // arrange, act, assert
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ProductDetailCacheProperties properties = context.getBean(ProductDetailCacheProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.maximumSize()).isEqualTo(1000);
            assertThat(properties.ttl()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @DisplayName("명시한 활성화와 양수 용량·TTL을 바인딩한다")
    @Test
    void bindsEnabledConfiguration() {
        // arrange, act, assert
        contextRunner.withPropertyValues("query.product-detail-cache.enabled=true",
                "query.product-detail-cache.maximum-size=12", "query.product-detail-cache.ttl=2s")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(ProductDetailCacheProperties.class))
                    .isEqualTo(new ProductDetailCacheProperties(true, 12, Duration.ofSeconds(2)));
            });
    }

    @DisplayName("활성화한 캐시의 용량이 0이나 음수이면 기동을 거절한다")
    @Test
    void rejectsNonPositiveCapacityWhenEnabled() {
        // arrange, act, assert
        for (String value : new String[] {"0", "-1"}) {
            contextRunner.withPropertyValues("query.product-detail-cache.enabled=true",
                    "query.product-detail-cache.maximum-size=" + value)
                .run(context -> assertThat(context).hasFailed());
        }
    }

    @DisplayName("활성화한 캐시의 TTL이 0이나 음수이면 기동을 거절한다")
    @Test
    void rejectsNonPositiveTtlWhenEnabled() {
        // arrange, act, assert
        for (String value : new String[] {"0s", "-1s"}) {
            contextRunner.withPropertyValues("query.product-detail-cache.enabled=true",
                    "query.product-detail-cache.ttl=" + value)
                .run(context -> assertThat(context).hasFailed());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProductDetailCacheProperties.class)
    static class PropertiesConfiguration {}
}
