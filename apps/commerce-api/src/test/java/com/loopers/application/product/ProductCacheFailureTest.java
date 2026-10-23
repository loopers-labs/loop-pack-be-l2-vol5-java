package com.loopers.application.product;

import com.loopers.application.order.ConfirmPaymentCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderItemRequest;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.cache.CacheRedisConfig;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 캐시가 채워진 상태에서 쓰기가 실패하거나 무효화가 어긋나는 경우를 docs/week4/design.md 8번 섹션 장애 계약 표대로 확인한다.
// 인수 테스트는 요청을 순서대로만 보내므로 다루지 못하는 범위다(3번 섹션 "인수 테스트가 다루지 않는 범위").
// 각 테스트는 조회 결과·Redis 상태·DB 상태를 함께 본다. @Transactional을 붙이지 않는다 — afterCommit이 실제 commit에서 실행되어야 한다.
@SpringBootTest
class ProductCacheFailureTest {

    private static final long OLD_PRICE = 1_000L;
    private static final long NEW_PRICE = 1_500L;
    private static final long TTL_SECONDS = 60L;
    private static final long AWAIT_SECONDS = 10L;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private ProductAdminFacade productAdminFacade;

    @Autowired
    private OrderFacade orderFacade;

    @MockitoSpyBean
    private ProductService productService;

    @MockitoSpyBean
    private ProductCache productCache;

    @MockitoSpyBean
    private PaymentAttemptRepository paymentAttemptRepository;

    // 어댑터(RedisProductCache)가 쓰는 캐시 전용 템플릿 — Redis 명령 실패를 주입하기 위해 감싼다.
    @MockitoSpyBean(name = CacheRedisConfig.CACHE_REDIS_TEMPLATE)
    private RedisTemplate<String, String> cacheRedisTemplate;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private ProductModel airMax;

    @BeforeEach
    void setUp() {
        BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", "스포츠 브랜드", "신발/의류"));
        airMax = productJpaRepository.save(new ProductModel("에어맥스", OLD_PRICE, nike.getId(), 10));
        productJpaRepository.save(new ProductModel("조던", 2_000L, nike.getId(), 10));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private String productKey() {
        return "product:" + airMax.getId();
    }

    private boolean cached(String key) {
        return Boolean.TRUE.equals(cacheRedisTemplate.hasKey(key));
    }

    private Long ttlSeconds(String key) {
        return cacheRedisTemplate.getExpire(key, TimeUnit.SECONDS);
    }

    private long detailPrice() {
        return productFacade.getProductDetail(airMax.getId()).price();
    }

    private long listPrice() {
        return productFacade.getProductList(null, ProductSort.PRICE_ASC, PageRequest.of(0, 20)).getContent().stream()
            .filter(info -> info.id().equals(airMax.getId()))
            .findFirst()
            .orElseThrow()
            .price();
    }

    // Redis에 들어 있는 product:{id} 값 — 조회 경로를 거치지 않고 포트로 직접 읽는다.
    private CachedProduct cachedProduct() {
        CacheResult<Map<Long, CachedProduct>> result = productCache.getProducts(List.of(airMax.getId()));
        if (result instanceof CacheResult.Hit<Map<Long, CachedProduct>>(Map<Long, CachedProduct> value)) {
            return value.get(airMax.getId());
        }
        throw new AssertionError("캐시 조회가 실패했다: " + result);
    }

    private long dbPrice() {
        return productJpaRepository.findById(airMax.getId()).orElseThrow().getPrice();
    }

    @DisplayName("C2 — 쓰기 트랜잭션이 롤백되면,")
    @Nested
    class Rollback {
        @DisplayName("재고 차감 뒤 결제 시도 저장 실패로 주문 확정이 롤백돼도, 캐시가 남아 있고 상세 조회의 재고가 DB 재고와 같다.")
        @Test
        void cachedStockStillMatchesDb() {
            // arrange — 캐시를 채운 뒤 주문을 만든다
            assertThat(productFacade.getProductDetail(airMax.getId()).remainingStock()).isEqualTo(10);
            Long userId = userJpaRepository.save(new UserModel()).getId();
            Long orderId = orderFacade.createOrder(userId, List.of(new OrderItemRequest(airMax.getId(), 3))).id();
            doThrow(new IllegalStateException("결제 시도 저장 실패 주입")).when(paymentAttemptRepository).save(any(PaymentAttempt.class));

            // act — 재고 차감(무효화 등록) 이후 결제 시도 저장에서 실패한다
            assertThrows(IllegalStateException.class, () -> orderFacade.confirmOrder(orderId, userId, new ConfirmPaymentCommand("key-" + orderId, "TEST", "0000-0000-0000-0000")));

            // assert
            int dbStock = productJpaRepository.findById(airMax.getId()).orElseThrow().getRemainingStock();
            assertAll(
                () -> assertThat(dbStock).isEqualTo(10),
                () -> assertThat(cached(productKey())).isTrue(),
                () -> assertThat(productFacade.getProductDetail(airMax.getId()).remainingStock()).isEqualTo(dbStock)
            );
        }
    }

    @DisplayName("C3 — commit 뒤 무효화(DEL·버전 올리기)가 실패하면,")
    @Nested
    class InvalidationFailure {
        @DisplayName("쓰기는 성공하고 DB는 새 가격이지만, 캐시가 만료될 때까지(최대 TTL 60초) 상세·목록에 옛 가격이 보이고 만료 뒤 새 가격이 보인다.")
        @Test
        void writeSucceeds_andStaleValueBoundedByTtl() {
            // arrange — 상세·목록 캐시를 채우고, 무효화가 Redis에 닿지 못한 상태(어댑터가 실패를 로그로만 남긴 상태)를 만든다
            assertThat(detailPrice()).isEqualTo(OLD_PRICE);
            assertThat(listPrice()).isEqualTo(OLD_PRICE);
            doNothing().when(productCache).evictProducts(any());
            doNothing().when(productCache).bumpListVersion();

            // act
            assertThatCode(() -> productAdminFacade.updateProduct(airMax.getId(), "에어맥스", NEW_PRICE))
                .doesNotThrowAnyException();

            // assert — 만료 전
            Long ttl = ttlSeconds(productKey());
            assertAll(
                () -> verify(productCache, times(1)).evictProducts(List.of(airMax.getId())),
                () -> verify(productCache, times(1)).bumpListVersion(),
                () -> assertThat(dbPrice()).isEqualTo(NEW_PRICE),
                () -> assertThat(detailPrice()).isEqualTo(OLD_PRICE),
                () -> assertThat(listPrice()).isEqualTo(OLD_PRICE),
                () -> assertThat(ttl).isBetween(1L, TTL_SECONDS)
            );

            // act & assert — TTL 만료를 흉내 내면 DB의 새 가격이 보인다
            redisCleanUp.truncateAll();
            assertAll(
                () -> assertThat(detailPrice()).isEqualTo(NEW_PRICE),
                () -> assertThat(listPrice()).isEqualTo(NEW_PRICE)
            );
        }

        @DisplayName("Redis DEL 명령이 예외를 던져도, 어댑터(evictProducts)는 예외를 밖으로 던지지 않는다.")
        @Test
        void adapterSwallowsDeleteFailure() {
            // arrange
            assertThat(detailPrice()).isEqualTo(OLD_PRICE);
            doThrow(new IllegalStateException("Redis DEL 실패 주입")).when(cacheRedisTemplate).delete(anyCollection());

            // act & assert
            assertAll(
                () -> assertThatCode(() -> productCache.evictProducts(List.of(airMax.getId()))).doesNotThrowAnyException(),
                () -> verify(cacheRedisTemplate, times(1)).delete(List.of(productKey())),
                () -> assertThat(cached(productKey())).isTrue()
            );
        }

        @DisplayName("Redis DEL 명령이 예외를 던져도, 관리자 가격 수정은 성공하고 DB에 반영된다.")
        @Test
        void writeSucceeds_whenDeleteThrows() {
            // arrange
            assertThat(detailPrice()).isEqualTo(OLD_PRICE);
            doThrow(new IllegalStateException("Redis DEL 실패 주입")).when(cacheRedisTemplate).delete(anyCollection());

            // act
            assertThatCode(() -> productAdminFacade.updateProduct(airMax.getId(), "에어맥스", NEW_PRICE))
                .doesNotThrowAnyException();

            // assert
            assertAll(
                () -> assertThat(dbPrice()).isEqualTo(NEW_PRICE),
                () -> assertThat(cached(productKey())).isTrue(),
                () -> assertThat(detailPrice()).isEqualTo(OLD_PRICE)
            );
        }
    }

    @DisplayName("C4 — 늦은 채움: 막지 않기로 한 경쟁(설계 8번 섹션) — 피해 상한이 TTL임을 확인한다.")
    @Nested
    class LateFill {
        @DisplayName("DB를 읽은 조회가 캐시에 넣기 전에 가격 수정(commit + DEL)이 끝나면, 옛 가격이 캐시에 들어가 TTL 동안 보이고 만료 뒤 새 가격이 보인다.")
        @Test
        void staleFillAfterInvalidation_boundedByTtl() throws Exception {
            // arrange — 첫 번째 getProduct만 DB를 읽은 직후(캐시에 넣기 전) 멈춘다
            CountDownLatch loaded = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean first = new AtomicBoolean(true);
            doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                if (first.compareAndSet(true, false)) {
                    loaded.countDown();
                    if (!release.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("조회 재개 신호를 받지 못했다");
                    }
                }
                return result;
            }).when(productService).getProduct(anyLong());

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<ProductDetailInfo> slowRead = executor.submit(() -> productFacade.getProductDetail(airMax.getId()));
                assertThat(loaded.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

                // act — 조회가 멈춘 사이 가격을 바꾼다. 캐시에 아직 값이 없어 DEL은 아무것도 지우지 않는다
                productAdminFacade.updateProduct(airMax.getId(), "에어맥스", NEW_PRICE);
                assertThat(cached(productKey())).isFalse();
                release.countDown();
                ProductDetailInfo slowResult = slowRead.get(AWAIT_SECONDS, TimeUnit.SECONDS);

                // assert — 멈췄던 조회가 옛 가격을 캐시에 넣었다
                Long ttl = ttlSeconds(productKey());
                assertAll(
                    () -> assertThat(slowResult.price()).isEqualTo(OLD_PRICE),
                    () -> assertThat(dbPrice()).isEqualTo(NEW_PRICE),
                    () -> assertThat(cachedProduct().price()).isEqualTo(OLD_PRICE),
                    () -> assertThat(detailPrice()).isEqualTo(OLD_PRICE),
                    () -> assertThat(ttl).isBetween(1L, TTL_SECONDS)
                );

                // act & assert — TTL 만료를 흉내 내면 새 가격이 보인다
                redisCleanUp.truncateAll();
                assertThat(detailPrice()).isEqualTo(NEW_PRICE);
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        }
    }
}
