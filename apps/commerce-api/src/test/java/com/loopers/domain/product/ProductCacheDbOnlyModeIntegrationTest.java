package com.loopers.domain.product;

import com.loopers.application.product.ProductFacade;
import com.loopers.config.redis.RedisConfig;
import com.loopers.fixture.ProductDetailCacheCleanUp;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.SqlCapture;
import com.loopers.infrastructure.product.ProductDetailRedisCache;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 비교용 DB-only 설정(enabled=false)은 기본정보 읽기·채움만 우회하고, commit 뒤 무효화는 유지해
 * 설정을 다시 켰을 때 그 사이 변경된 상품의 옛 기본정보가 남지 않게 한다.
 */
@DisplayName("DB-only 비교 설정은 캐시 읽기·채움을 우회하고 commit 뒤 무효화는 유지한다.")
@SpringBootTest(properties = {SqlCapture.PROPERTY, "commerce.cache.product-detail.enabled=false"})
class ProductCacheDbOnlyModeIntegrationTest {

    @Autowired
    private ProductFacade productFacade;
    @Autowired
    private ProductFixture productFixture;
    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private ProductDetailCacheCleanUp productDetailCacheCleanUp;

    @AfterEach
    void tearDown() {
        SqlCapture.stop();
        databaseCleanUp.truncateAllTables();
        productDetailCacheCleanUp.deleteAll();
    }

    @DisplayName("기존 key 가 있어도 읽지 않고 DB 완성 상세로 응답하며 새로 채우지 않는다. 쓰기는 기존 key 를 삭제한다.")
    @Test
    void bypassesReadsButKeepsEviction() {
        ProductModel shoes = productFixture.createProduct("운동화", 1_000L, 10L);
        ProductModel cap = productFixture.createProduct("모자", 2_000L, 10L);
        String key = ProductDetailRedisCache.keyOf(shoes.getId());
        String stale = "{\"name\":\"옛 운동화\",\"brandName\":\"옛 브랜드\",\"price\":1,\"stockQuantity\":1}";
        redisTemplate.opsForValue().set(key, stale, Duration.ofSeconds(10));

        SqlCapture.start();
        ProductQueryResult result = productFacade.getProduct(shoes.getId());
        ProductQueryResult other = productFacade.getProduct(cap.getId());
        List<String> sql = SqlCapture.stop();
        String keptValue = redisTemplate.opsForValue().get(key);

        productFacade.update(shoes.getId(), "운동화", 5_000L);

        assertAll(
            () -> assertThat(result.price()).isEqualTo(1_000L),
            () -> assertThat(result.name()).isEqualTo("운동화"),
            () -> assertThat(other.price()).isEqualTo(2_000L),
            () -> assertThat(sql).hasSize(2),
            () -> assertThat(keptValue).isEqualTo(stale),
            () -> assertThat(redisTemplate.hasKey(ProductDetailRedisCache.keyOf(cap.getId()))).isFalse(),
            () -> assertThat(redisTemplate.hasKey(key)).isFalse(),
            () -> assertThat(productFacade.getProduct(shoes.getId()).price()).isEqualTo(5_000L)
        );
    }
}
