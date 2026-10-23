package com.loopers.application.product;

import com.loopers.application.brand.BrandAdminFacade;
import com.loopers.application.like.LikeFacade;
import com.loopers.application.order.ConfirmPaymentCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderItemRequest;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.cache.CacheRedisConfig;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.pg.TestPg;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

// 쓰기 경로가 commit 뒤 무효화하는 키가 docs/week4/design.md 7번 섹션 표와 같은지 Redis 상태로 확인한다.
// 테스트 메서드에 @Transactional을 붙이지 않는다 — afterCommit이 실제 commit에서 실행되는지 봐야 하기 때문이다.
@SpringBootTest
class ProductCacheInvalidationTest {

    private static final String LIST_VERSION_KEY = "product:list:version";

    @Autowired
    private ProductAdminFacade productAdminFacade;

    @Autowired
    private BrandAdminFacade brandAdminFacade;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private LikeFacade likeFacade;

    @Autowired
    private ProductCache productCache;

    @MockitoSpyBean
    private BrandRepository brandRepository;

    @MockitoSpyBean
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    @Qualifier(CacheRedisConfig.CACHE_REDIS_TEMPLATE)
    private RedisTemplate<String, String> cacheRedisTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private BrandModel nike;
    private BrandModel adidas;
    private ProductModel airMax;
    private ProductModel jordan;
    private ProductModel ultraBoost;

    @BeforeEach
    void setUp() {
        // 다른 테스트 클래스의 관리자 쓰기가 남긴 목록 버전을 지우고 시작한다 — 버전 값을 정확히 비교하기 위해서
        redisCleanUp.truncateAll();

        nike = brandJpaRepository.save(new BrandModel("나이키", "스포츠 브랜드", "신발/의류"));
        adidas = brandJpaRepository.save(new BrandModel("아디다스", "스포츠 브랜드", "신발/의류"));
        airMax = productJpaRepository.save(new ProductModel("에어맥스", 1_000L, nike.getId(), 10));
        jordan = productJpaRepository.save(new ProductModel("조던", 2_000L, nike.getId(), 10));
        ultraBoost = productJpaRepository.save(new ProductModel("울트라부스트", 3_000L, adidas.getId(), 10));

        // 조회 경로가 채웠을 상태를 포트로 미리 만든다 — 목록 버전은 1에서 시작한다
        productCache.putProducts(List.of(
            CachedProduct.of(airMax, 0L),
            CachedProduct.of(jordan, 0L),
            CachedProduct.of(ultraBoost, 0L)
        ));
        productCache.putBrands(List.of(CachedBrand.from(nike), CachedBrand.from(adidas)));
        productCache.bumpListVersion();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private boolean cached(String key) {
        return Boolean.TRUE.equals(cacheRedisTemplate.hasKey(key));
    }

    private String listVersion() {
        return cacheRedisTemplate.opsForValue().get(LIST_VERSION_KEY);
    }

    @DisplayName("관리자 상품 쓰기 후,")
    @Nested
    class AdminProductWrite {
        @DisplayName("상품을 수정하면, 그 상품 키가 지워지고 목록 버전이 오른다.")
        @Test
        void update_evictsProductAndBumpsListVersion() {
            // act
            productAdminFacade.updateProduct(airMax.getId(), "에어맥스2", 1_500L);

            // assert
            assertAll(
                () -> assertThat(cached("product:" + airMax.getId())).isFalse(),
                () -> assertThat(cached("product:" + jordan.getId())).isTrue(),
                () -> assertThat(listVersion()).isEqualTo("2")
            );
        }

        @DisplayName("재고를 바꾸면, 그 상품 키만 지워지고 목록 버전은 그대로다.")
        @Test
        void changeStock_evictsProductOnly() {
            // act
            productAdminFacade.changeStock(airMax.getId(), 3);

            // assert
            assertAll(
                () -> assertThat(cached("product:" + airMax.getId())).isFalse(),
                () -> assertThat(cached("product:" + jordan.getId())).isTrue(),
                () -> assertThat(listVersion()).isEqualTo("1")
            );
        }

        @DisplayName("상품을 등록하면, 목록 버전이 오르고 기존 상품 키는 남는다.")
        @Test
        void create_bumpsListVersion() {
            // act
            productAdminFacade.createProduct("코르테즈", 900L, nike.getId(), 5);

            // assert
            assertAll(
                () -> assertThat(listVersion()).isEqualTo("2"),
                () -> assertThat(cached("product:" + airMax.getId())).isTrue()
            );
        }

        @DisplayName("상품을 삭제하면, 그 상품 키가 지워지고 목록 버전이 오른다.")
        @Test
        void delete_evictsProductAndBumpsListVersion() {
            // act
            productAdminFacade.deleteProduct(airMax.getId());

            // assert
            assertAll(
                () -> assertThat(cached("product:" + airMax.getId())).isFalse(),
                () -> assertThat(cached("product:" + jordan.getId())).isTrue(),
                () -> assertThat(listVersion()).isEqualTo("2")
            );
        }
    }

    @DisplayName("주문 확정(PG 결제 시작) 후,")
    @Nested
    class ConfirmOrder {
        @DisplayName("주문 항목 상품의 키만 지워지고, 다른 상품 키와 목록 버전은 그대로다.")
        @Test
        void evictsOrderedProductsOnly() {
            // arrange
            Long userId = userJpaRepository.save(new UserModel()).getId();
            Long orderId = orderFacade.createOrder(userId, List.of(
                new OrderItemRequest(airMax.getId(), 1),
                new OrderItemRequest(jordan.getId(), 2)
            )).id();

            // act
            orderFacade.confirmOrder(orderId, userId, new ConfirmPaymentCommand("key-" + orderId, "TEST", "0000-0000-0000-0000"));

            // assert
            assertAll(
                () -> assertThat(cached("product:" + airMax.getId())).isFalse(),
                () -> assertThat(cached("product:" + jordan.getId())).isFalse(),
                () -> assertThat(cached("product:" + ultraBoost.getId())).isTrue(),
                () -> assertThat(listVersion()).isEqualTo("1")
            );
        }

        @DisplayName("주문 생성(DRAFT)만으로는 아무 키도 지워지지 않는다.")
        @Test
        void createOrder_doesNotEvict() {
            // arrange
            Long userId = userJpaRepository.save(new UserModel()).getId();

            // act
            orderFacade.createOrder(userId, List.of(new OrderItemRequest(airMax.getId(), 1)));

            // assert
            assertAll(
                () -> assertThat(cached("product:" + airMax.getId())).isTrue(),
                () -> assertThat(listVersion()).isEqualTo("1")
            );
        }

        @DisplayName("재고 차감 뒤 결제 시도 저장 실패로 롤백되면, 무효화가 실행되지 않아 상품 키가 남는다.")
        @Test
        void doesNotEvict_whenRolledBack() {
            // arrange — 재고 차감까지 진행된 뒤(무효화 등록 이후) 결제 시도 저장에서 실패한다
            Long userId = userJpaRepository.save(new UserModel()).getId();
            Long orderId = orderFacade.createOrder(userId, List.of(
                new OrderItemRequest(airMax.getId(), 1)
            )).id();
            doThrow(new IllegalStateException("결제 시도 저장 실패 주입")).when(paymentAttemptRepository).save(any(PaymentAttempt.class));

            // act
            assertThrows(IllegalStateException.class, () -> orderFacade.confirmOrder(orderId, userId, new ConfirmPaymentCommand("key-" + orderId, "TEST", "0000-0000-0000-0000")));

            // assert
            assertAll(
                () -> assertThat(productJpaRepository.findById(airMax.getId()).orElseThrow().getRemainingStock()).isEqualTo(10),
                () -> assertThat(cached("product:" + airMax.getId())).isTrue()
            );
        }

        @DisplayName("PG가 효과 없는 거절로 결제를 실패시켜 재고가 돌아오면, 그 상품 키가 다시 지워진다.")
        @Test
        void evictsAgain_whenStockIsReturnedOnFailure() {
            // arrange — TX A의 무효화 뒤 다시 채운 캐시가, 실패 반영(TX B)으로 재고가 돌아온 뒤에도 남으면 옛 재고를 보여 준다
            Long userId = userJpaRepository.save(new UserModel()).getId();
            Long orderId = orderFacade.createOrder(userId, List.of(new OrderItemRequest(airMax.getId(), 1))).id();
            TestPg.server().stubFor(post(urlEqualTo(TestPg.PAYMENTS_PATH))
                .willReturn(aResponse().withStatus(422).withHeader("Content-Type", "application/json")
                    .withBody("{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"" + String.format("LP-ORD-%010d", orderId)
                        + "\",\"amount\":1000,\"transactionCreated\":false}")));
            doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                productCache.putProducts(List.of(CachedProduct.of(productJpaRepository.findById(airMax.getId()).orElseThrow(), 0L)));
                return result;
            }).when(paymentAttemptRepository).findByOrderIdForUpdate(orderId);

            // act
            orderFacade.confirmOrder(orderId, userId, new ConfirmPaymentCommand("key-" + orderId, "TEST", "0000-0000-0000-0000"));

            // assert
            assertAll(
                () -> assertThat(productJpaRepository.findById(airMax.getId()).orElseThrow().getRemainingStock()).isEqualTo(10),
                () -> assertThat(cached("product:" + airMax.getId())).isFalse()
            );
        }
    }

    @DisplayName("관리자 브랜드 쓰기 후,")
    @Nested
    class AdminBrandWrite {
        @DisplayName("브랜드를 수정하면, 그 브랜드 키만 지워지고 상품 키는 남는다.")
        @Test
        void update_evictsBrandOnly() {
            // act
            brandAdminFacade.updateBrand(nike.getId(), "NIKE", "스포츠 브랜드", "신발/의류");

            // assert
            assertAll(
                () -> assertThat(cached("brand:" + nike.getId())).isFalse(),
                () -> assertThat(cached("brand:" + adidas.getId())).isTrue(),
                () -> assertThat(cached("product:" + airMax.getId())).isTrue(),
                () -> assertThat(cached("product:" + jordan.getId())).isTrue(),
                () -> assertThat(listVersion()).isEqualTo("1")
            );
        }

        @DisplayName("C7 — 브랜드를 삭제하면, 그 브랜드의 상품 키 전부와 브랜드 키가 지워지고 목록 버전이 오른다. 다른 브랜드 상품 키는 남는다.")
        @Test
        void delete_evictsBrandAndItsProducts() {
            // act
            brandAdminFacade.deleteBrand(nike.getId());

            // assert
            assertAll(
                () -> assertThat(cached("product:" + airMax.getId())).isFalse(),
                () -> assertThat(cached("product:" + jordan.getId())).isFalse(),
                () -> assertThat(cached("brand:" + nike.getId())).isFalse(),
                () -> assertThat(listVersion()).isEqualTo("2"),
                () -> assertThat(cached("product:" + ultraBoost.getId())).isTrue(),
                () -> assertThat(cached("brand:" + adidas.getId())).isTrue()
            );
        }

        @DisplayName("상품 삭제 뒤 브랜드 저장에서 실패해 롤백되면, 무효화가 실행되지 않아 키와 목록 버전이 그대로다.")
        @Test
        void delete_doesNotEvict_whenRolledBack() {
            // arrange — 상품 삭제·무효화 등록 이후 브랜드 저장에서 실패시킨다
            doThrow(new IllegalStateException("브랜드 저장 실패 주입")).when(brandRepository).save(any(BrandModel.class));

            // act
            assertThrows(IllegalStateException.class, () -> brandAdminFacade.deleteBrand(nike.getId()));

            // assert
            assertAll(
                () -> assertThat(productJpaRepository.findById(airMax.getId()).orElseThrow().getDeletedAt()).isNull(),
                () -> assertThat(cached("product:" + airMax.getId())).isTrue(),
                () -> assertThat(cached("product:" + jordan.getId())).isTrue(),
                () -> assertThat(cached("brand:" + nike.getId())).isTrue(),
                () -> assertThat(listVersion()).isEqualTo("1")
            );
        }
    }

    @DisplayName("좋아요 쓰기 후,")
    @Nested
    class Like {
        @DisplayName("C6 — 좋아요를 등록·취소해도 상품 키와 목록 버전이 그대로다(TTL로 늦게 반영).")
        @Test
        void likeAndUnlike_doNotEvict() {
            // arrange
            Long userId = userJpaRepository.save(new UserModel()).getId();

            // act & assert
            likeFacade.likeProduct(userId, airMax.getId());
            assertAll(
                () -> assertThat(cached("product:" + airMax.getId())).isTrue(),
                () -> assertThat(listVersion()).isEqualTo("1")
            );

            likeFacade.unlikeProduct(userId, airMax.getId());
            assertAll(
                () -> assertThat(cached("product:" + airMax.getId())).isTrue(),
                () -> assertThat(listVersion()).isEqualTo("1")
            );
        }
    }
}
