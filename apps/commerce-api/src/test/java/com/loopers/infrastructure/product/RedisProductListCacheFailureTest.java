package com.loopers.infrastructure.product;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderFacade.OrderRequestLine;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.point.PointFacade;
import com.loopers.application.product.AdminProductFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.application.product.ProductInfo;
import com.loopers.config.ProductCacheRedisConfig;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.product.ProductView;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 목록 캐시의 장애 · 경쟁 장면을 실제 DB · Redis 로 확인함 (4주차 docs/week4/cache-design.md 3.4, 4, 7).
 * <ul>
 *   <li>다른 테스트와 공유하지 않는 Redis 컨테이너를 이 테스트만 쓰고, 일시정지(pause)로 응답 없는 Redis 를 만든다.
 *       포트가 그대로라 재개(unpause)하면 같은 연결로 돌아온다</li>
 *   <li>실패한 무효화의 재처리 간격을 길게 두고 직접 호출해, 재처리 전후를 결정적으로 나눈다</li>
 * </ul>
 */
@SpringBootTest(properties = {
    "spring.jpa.properties.hibernate.generate_statistics=true",
    "product-list-cache.retry-delay-ms=3600000"
})
class RedisProductListCacheFailureTest {

    /** 캐시를 거치는 첫 페이지 · 기본 크기 (cache-design.md 2.1) */
    private static final Pageable FIRST_PAGE = PageRequest.of(0, 20);
    private static final long WAIT_SECONDS = 10;

    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> REDIS.getMappedPort(6379));
        registry.add("datasource.redis.replicas[0].host", REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private AdminProductFacade adminProductFacade;

    @Autowired
    private BrandFacade brandFacade;

    @Autowired
    private ProductService productService;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private PointFacade pointFacade;

    @Autowired
    private RedisProductListCache cache;

    @Autowired
    @Qualifier(ProductCacheRedisConfig.MASTER_TEMPLATE)
    private StringRedisTemplate redis;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private Long brandId;
    private Long productId;

    @BeforeEach
    void setUp() {
        brandId = brandFacade.createBrand("브랜드", null).id();
        productId = adminProductFacade.createProduct(brandId, "상품", 1_000L).id();
        adminProductFacade.createProduct(brandId, "다른 상품", 2_000L);
    }

    @AfterEach
    void tearDown() {
        resume();
        cache.retryPendingInvalidations();
        databaseCleanUp.truncateAllTables();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    private static void pause() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private static void resume() {
        if (Boolean.TRUE.equals(REDIS.getDockerClient().inspectContainerCmd(REDIS.getContainerId()).exec().getState().getPaused())) {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    private Page<ProductInfo> firstPage() {
        return productFacade.getProducts(brandId, ProductSort.PRICE_ASC, FIRST_PAGE);
    }

    private ProductInfo infoOf(Page<ProductInfo> page, Long id) {
        return page.getContent().stream().filter(info -> info.id().equals(id)).findFirst().orElseThrow();
    }

    private long priceOf(Page<ProductInfo> page, Long id) {
        return infoOf(page, id).price();
    }

    private long statementsOfFirstPage() {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        firstPage();
        return statistics.getPrepareStatementCount();
    }

    @DisplayName("Redis 가 응답하지 않으면, 조회는 대기 한도 안에 DB 결과를 돌려주고 관리자 수정도 성공한다. 이때 옛 값은 보이지 않는다. (4.2)")
    @Test
    void bypassesToDatabase_andKeepsWritesSucceeding_whileRedisIsUnresponsive() {
        // arrange
        firstPage();
        pause();

        // act
        long writeStart = System.nanoTime();
        adminProductFacade.updateProduct(productId, "상품", 5_000L);
        long writeMillis = (System.nanoTime() - writeStart) / 1_000_000;
        long readStart = System.nanoTime();
        Page<ProductInfo> duringOutage = firstPage();
        long readMillis = (System.nanoTime() - readStart) / 1_000_000;

        // assert
        assertAll(
            () -> assertThat(priceOf(duringOutage, productId)).isEqualTo(5_000L),
            () -> assertThat(readMillis).isLessThan(1_000),
            () -> assertThat(writeMillis).isLessThan(1_000),
            () -> assertThat(cache.hasPendingInvalidation(brandId)).isTrue()
        );
    }

    @DisplayName("무효화에 실패한 채 Redis 가 돌아오면, 재처리 전까지는 옛 값이 보이고 재처리 뒤에는 새 값이 보인다. (3.4, 5)")
    @Test
    void servesStaleUntilRetry_thenFresh_afterRedisRecovers() {
        // arrange
        firstPage();
        pause();
        adminProductFacade.updateProduct(productId, "상품", 5_000L);
        resume();

        // act
        Page<ProductInfo> beforeRetry = firstPage();
        cache.retryPendingInvalidations();
        Page<ProductInfo> afterRetry = firstPage();

        // assert
        assertAll(
            () -> assertThat(priceOf(beforeRetry, productId)).isEqualTo(1_000L),
            () -> assertThat(priceOf(afterRetry, productId)).isEqualTo(5_000L),
            () -> assertThat(cache.hasPendingInvalidation(brandId)).isFalse()
        );
    }

    @DisplayName("Redis 가 응답하지 않아도, 주문 확정은 DB 규칙대로 재고 · 포인트를 반영하고 성공한다. 품절 표시는 재처리 뒤 목록에 반영된다. (4.2, 5)")
    @Test
    void confirmsOrderByDatabaseRules_whileRedisIsUnresponsive() {
        // arrange
        Long buyer = 1L;
        adminProductFacade.changeStock(productId, 1);
        pointFacade.charge(buyer, 10_000L);
        Long orderId = orderFacade.createOrder(buyer, List.of(new OrderRequestLine(productId, 1))).id();
        firstPage();
        pause();

        // act
        OrderInfo confirmed = orderFacade.confirmOrder(buyer, orderId);
        boolean pendingDuringOutage = cache.hasPendingInvalidation(brandId);
        resume();
        Page<ProductInfo> beforeRetry = firstPage();
        cache.retryPendingInvalidations();
        Page<ProductInfo> afterRetry = firstPage();

        // assert
        assertAll(
            () -> assertThat(confirmed.status()).isEqualTo("CONFIRMED"),
            () -> assertThat(confirmed.paymentAmount()).isEqualTo(1_000L),
            () -> assertThat(productService.getActiveProduct(productId).getStock()).isZero(),
            () -> assertThat(pointFacade.getPoint(buyer).balance()).isEqualTo(9_000L),
            () -> assertThat(pendingDuringOutage).isTrue(),
            () -> assertThat(infoOf(beforeRetry, productId).soldOut()).isFalse(),
            () -> assertThat(infoOf(afterRetry, productId).soldOut()).isTrue()
        );
    }

    @DisplayName("Redis 가 돌아오면, 다시 캐시를 채우고 다음 조회부터 SQL 없이 돌려준다. (4.2)")
    @Test
    void refillsCache_afterRedisRecovers() {
        // arrange
        pause();
        firstPage();
        resume();

        // act
        long refill = statementsOfFirstPage();
        long hit = statementsOfFirstPage();

        // assert
        assertAll(
            () -> assertThat(refill).isEqualTo(2),
            () -> assertThat(hit).isZero()
        );
    }

    @DisplayName("변경 전에 DB 를 읽은 조회가 변경 commit · 버전 증가 뒤에 늦게 저장해도, 이후 조회는 새 값을 읽는다. (3.1 늦은 채움)")
    @Test
    void ignoresLateFillOfOlderRead() throws Exception {
        // arrange
        CountDownLatch oldReadDone = new CountDownLatch(1);
        CountDownLatch releaseOldRead = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // 이전 조회: 버전을 읽고 DB 에서 옛 가격을 읽은 뒤, 저장하기 전에 멈춘다
            Future<Page<ProductView>> oldRead = executor.submit(() -> cache.getFirstPage(brandId, ProductSort.PRICE_ASC, () -> {
                Page<ProductView> page = productService.getActiveProductViews(brandId, ProductSort.PRICE_ASC, FIRST_PAGE);
                oldReadDone.countDown();
                await(releaseOldRead);
                return page;
            }));
            assertThat(oldReadDone.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            // act: 변경 commit 과 버전 증가가 끝난 뒤에 이전 조회가 옛 값을 저장한다
            adminProductFacade.updateProduct(productId, "상품", 5_000L);
            releaseOldRead.countDown();
            Page<ProductView> stale = oldRead.get(WAIT_SECONDS, TimeUnit.SECONDS);
            Page<ProductInfo> next = firstPage();

            // assert
            assertAll(
                () -> assertThat(stale.getContent()).filteredOn(view -> view.id().equals(productId))
                    .extracting(ProductView::price).containsExactly(1_000L),
                () -> assertThat(priceOf(next, productId)).isEqualTo(5_000L)
            );
        } finally {
            releaseOldRead.countDown();
            executor.shutdownNow();
            executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("이전 조회를 재개하는 신호를 받지 못함");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
