package com.loopers.domain.product;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.like.LikeFacade;
import com.loopers.application.order.OrderConfirmFacade;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.point.PointFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.config.redis.RedisConfig;
import com.loopers.fixture.BrandFixture;
import com.loopers.fixture.ProductDetailCacheCleanUp;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.SqlCapture;
import com.loopers.fixture.UserFixture;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductDetailRedisCache;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상품 상세 기본정보 캐시 검증의 공통 준비. 실제 MySQL·Redis 컨테이너를 사용하고,
 * 캐시 포트와 조회 포트는 실제 구현을 호출하는 spy 로 두어 순서 통제·실패 주입에만 사용한다.
 * 같은 설정을 공유해 하나의 Spring 컨텍스트를 재사용한다.
 */
@SpringBootTest(properties = SqlCapture.PROPERTY)
public abstract class ProductCacheTestSupport {

    /** test 프로필의 고정 TTL. */
    protected static final Duration TTL = Duration.ofSeconds(10);

    @MockitoSpyBean
    protected ProductDetailCache productDetailCache;
    @MockitoSpyBean
    protected ProductQueryRepository productQueryRepository;

    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    protected RedisTemplate<String, String> redisTemplate;

    @Autowired
    protected ProductFacade productFacade;
    @Autowired
    protected BrandFacade brandFacade;
    @Autowired
    protected LikeFacade likeFacade;
    @Autowired
    protected OrderFacade orderFacade;
    @Autowired
    protected OrderConfirmFacade orderConfirmFacade;
    @Autowired
    protected PointFacade pointFacade;
    @Autowired
    protected BrandFixture brandFixture;
    @Autowired
    protected ProductFixture productFixture;
    @Autowired
    protected UserFixture userFixture;
    @Autowired
    protected ProductJpaRepository productJpaRepository;
    @Autowired
    protected OrderJpaRepository orderJpaRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private ProductDetailCacheCleanUp productDetailCacheCleanUp;

    @BeforeEach
    void clearDetailKeysBefore() {
        productDetailCacheCleanUp.deleteAll();
    }

    @AfterEach
    void cleanUp() {
        SqlCapture.stop();
        databaseCleanUp.truncateAllTables();
        productDetailCacheCleanUp.deleteAll();
    }

    protected String key(Long productId) {
        return ProductDetailRedisCache.keyOf(productId);
    }

    protected String rawValue(Long productId) {
        return redisTemplate.opsForValue().get(key(productId));
    }

    protected boolean hasKey(Long productId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(productId)));
    }

    /** 남은 만료 시간(ms). key 가 없으면 -2, 만료가 없으면 -1. */
    protected long pttl(Long productId) {
        Long ttl = redisTemplate.getExpire(key(productId), TimeUnit.MILLISECONDS);
        return ttl == null ? -2L : ttl;
    }

    /** key 가 실제로 만료될 때까지 기다리고, 기다린 시간을 돌려준다. 유한한 시간 안에 만료되지 않으면 실패한다. */
    protected Duration awaitExpiry(Long productId, Duration max) throws InterruptedException {
        long start = System.nanoTime();
        long deadline = start + max.toNanos();
        while (hasKey(productId)) {
            assertThat(System.nanoTime()).as("key 가 제한 시간 안에 만료되어야 한다").isLessThan(deadline);
            Thread.sleep(100);
        }
        return Duration.ofNanos(System.nanoTime() - start);
    }

    /** 완성 상세 SQL: 상품·브랜드 조인, 좋아요 집계와 기본정보(가격·재고) 열을 함께 읽는다. */
    protected static boolean isFullDetailSql(String sql) {
        return isDetailByIdSql(sql) && sql.contains("stock_quantity") && sql.contains(".price");
    }

    /** 노출 확인·좋아요 수 SQL: 같은 조인·집계를 쓰지만 기본정보 열을 선택하지 않는다. */
    protected static boolean isVisibleWithLikeCountSql(String sql) {
        return isDetailByIdSql(sql) && !sql.contains("stock_quantity") && !sql.contains(".price") && !sql.contains(".name");
    }

    protected static long count(List<String> statements, java.util.function.Predicate<String> predicate) {
        return statements.stream().filter(predicate).count();
    }

    private static boolean isDetailByIdSql(String sql) {
        String normalized = sql.toLowerCase();
        return normalized.startsWith("select") && normalized.contains(" from product ")
            && normalized.contains("join brand") && normalized.contains("product_like")
            && normalized.contains(".id=?");
    }
}
