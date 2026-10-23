package com.loopers.application.product;

import com.github.dockerjava.api.DockerClient;
import com.loopers.application.product.cache.ProductListCache;
import com.loopers.application.product.dto.ProductListItemInfo;
import com.loopers.application.product.dto.ProductListQuery;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.product.Stock;
import com.loopers.support.fixture.ExposedProducts;
import com.loopers.testcontainers.RedisTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Redis 가 응답하지 않을 때 상품 목록이 제한된 대기 뒤 DB 결과로 응답하고, 돌아온 뒤 정확히 다시 채워지는지 확인한다.
 *
 * <p>테스트가 실제로 연결한 Redis 컨테이너를 pause 해 명령이 응답 없이 쌓이는 상황을 만든다. stop/start 는 매핑 포트가
 * 바뀌어 다른 테스트가 접속하지 못하므로 쓰지 않고, 실패해도 항상 unpause 한다. 대기 상한은 명령 timeout(300ms)이
 * 조회·저장에 한 번씩 걸리는 시간에 DB 조회를 더한 값이라, 여유를 두어 2초 안을 확인한다.
 */
@SpringBootTest(properties = "product.list-cache.ttl=5s")
class ProductCacheRedisOutageTest {

    private static final Duration BOUNDED_WAIT = Duration.ofSeconds(2);
    private static final Duration RECOVERY_TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private AdminProductFacade adminProductFacade;

    @Autowired
    private ProductListCache productListCache;

    @Autowired
    private ExposedProducts exposedProducts;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private final GenericContainer<?> redis = RedisTestContainersConfig.container();

    private Brand loopers;
    private Product shoes;
    private Product shirt;
    private Product socks;

    // 루퍼스: 러닝화 30,000 / 티셔츠 20,000 / 양말 10,000
    @BeforeEach
    void setUp() {
        loopers = brandRepository.save(Brand.create("루퍼스"));
        shoes = saveProduct("러닝화", 30_000);
        shirt = saveProduct("티셔츠", 20_000);
        socks = saveProduct("양말", 10_000);
    }

    @AfterEach
    void tearDown() {
        resumeRedis();
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private Product saveProduct(String name, long price) {
        return exposedProducts.save(Product.create(loopers.getId(), name, new Money(price), new Stock(10)));
    }

    private void pauseRedis() {
        docker().pauseContainerCmd(redis.getContainerId()).exec();
    }

    private void resumeRedis() {
        Boolean paused = docker().inspectContainerCmd(redis.getContainerId()).exec().getState().getPaused();
        if (Boolean.TRUE.equals(paused)) {
            docker().unpauseContainerCmd(redis.getContainerId()).exec();
        }
    }

    private DockerClient docker() {
        return redis.getDockerClient();
    }

    private Page<ProductListItemInfo> priceAsc() {
        return productFacade.getList(loopers.getId(), "price_asc", 0, 20);
    }

    private List<Long> ids(Page<ProductListItemInfo> page) {
        return page.getContent().stream().map(ProductListItemInfo::id).toList();
    }

    private long priceOf(Page<ProductListItemInfo> page, Product product) {
        return page.getContent().stream().filter(info -> info.id().equals(product.getId()))
            .findFirst().orElseThrow().price();
    }

    private boolean cached() {
        return productListCache.find(new ProductListQuery(loopers.getId(), ProductSort.PRICE_ASC, 0, 20)).isPresent();
    }

    private double errorCount(String operation) {
        Counter counter = meterRegistry.find("product.list.cache.errors").tag("operation", operation).counter();
        return counter == null ? 0 : counter.count();
    }

    @DisplayName("Redis 가 멈추면 목록은 제한된 시간 안에 DB 결과로 응답하고, 오류를 따로 센다.")
    @Test
    void fallsBackToDatabase_withinBoundedWait() {
        // arrange
        Page<ProductListItemInfo> before = priceAsc();
        double findErrors = errorCount("find");
        double saveErrors = errorCount("save");
        pauseRedis();

        // act
        long startedAt = System.nanoTime();
        Page<ProductListItemInfo> during = priceAsc();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        // assert
        assertThat(elapsed).as("Redis 중단 중 목록 응답 시간 %dms", elapsed.toMillis()).isLessThan(BOUNDED_WAIT);
        assertThat(during.getContent()).isEqualTo(before.getContent());
        assertThat(during.getTotalElements()).isEqualTo(before.getTotalElements());
        assertThat(errorCount("find")).isEqualTo(findErrors + 1);
        assertThat(errorCount("save")).isEqualTo(saveErrors + 1);
    }

    @DisplayName("Redis 중단 중 관리자 수정은 제한된 시간 안에 성공하고, 돌아온 뒤 남은 옛 값은 TTL 만료 뒤 DB 와 같게 다시 채워진다.")
    @Test
    void recoversAfterRestart_whenEvictionFailedDuringOutage() {
        // arrange: 옛 가격이 캐시에 있다
        priceAsc();
        double evictErrors = errorCount("evict");
        pauseRedis();

        // act: 중단 중 수정(무효화 실패). 중단 중 조회는 하지 않는다: 그 조회의 시간 초과된 저장이 복구 뒤 실행되어
        // 새 값으로 덮기 때문이다(아래 테스트). 여기서는 무효화만 실패한 장면을 본다.
        long startedAt = System.nanoTime();
        adminProductFacade.update(socks.getId(), "양말", 40_000);
        Duration adminElapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        resumeRedis();
        Page<ProductListItemInfo> revived = priceAsc();

        // assert: 관리자 응답은 제한된 시간 안에 성공하고 DB 에 commit 된다
        assertThat(adminElapsed).as("Redis 중단 중 관리자 수정 시간 %dms", adminElapsed.toMillis()).isLessThan(BOUNDED_WAIT);
        assertThat(errorCount("evict")).isEqualTo(evictErrors + 1);
        assertThat(adminProductFacade.get(socks.getId()).price()).isEqualTo(40_000);

        // assert: 돌아온 직후에는 지우지 못한 옛 값이 다시 보인다(허용한 낡음, 5-3장)
        assertThat(priceOf(revived, socks)).as("무효화 실패로 남은 옛 가격").isEqualTo(10_000);

        // assert: TTL 만료 뒤 DB 와 같은 값으로 다시 채워지고, 그 값이 hit 된다
        await().atMost(RECOVERY_TIMEOUT).until(() -> !cached());
        Page<ProductListItemInfo> refilled = priceAsc();
        assertThat(cached()).isTrue();
        Page<ProductListItemInfo> hit = priceAsc();
        assertThat(ids(refilled)).containsExactly(shirt.getId(), shoes.getId(), socks.getId());
        assertThat(priceOf(refilled, socks)).isEqualTo(40_000);
        assertThat(hit.getContent()).isEqualTo(refilled.getContent());
    }

    @DisplayName("명령 timeout 은 기다림만 끊고 명령을 취소하지 않아, 중단 중 시간 초과된 저장이 복구 뒤 실행될 수 있다.")
    @Test
    void timedOutSaveIsAppliedAfterResume() {
        // arrange
        pauseRedis();

        // act: 중단 중 조회 — 조회와 저장이 모두 시간 초과되어 DB 결과로 응답한다
        Page<ProductListItemInfo> during = priceAsc();
        resumeRedis();

        // assert: 소켓에 남아 있던 저장(SET)이 복구 뒤 실행되어 캐시가 생긴다. 그 값은 TTL 을 가진 채 저장 시점의 DB 결과다
        await().atMost(RECOVERY_TIMEOUT).until(this::cached);
        assertThat(priceAsc().getContent()).isEqualTo(during.getContent());
    }
}
