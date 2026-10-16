package com.loopers.config.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.brand.application.BrandUseCase;
import com.loopers.brand.domain.Brand;
import com.loopers.config.redis.RedisConfig;
import com.loopers.like.application.LikeUseCase;
import com.loopers.order.application.OrderUseCase;
import com.loopers.product.application.ProductUseCase;
import com.loopers.product.domain.Product;
import com.loopers.support.cache.CacheKeys;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.User;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.loopers.support.http.ApiHttp.admin;
import static com.loopers.support.http.ApiHttp.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "catalog.cache.enabled=true")
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
@DisplayName("캐시 유효 범위: 쓰기 한 번이 갱신할 조회와 유지할 조회")
class CacheInvalidationScopeHttpTest {
    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) { ReadCacheHttpTest.properties(registry); }
    @Autowired com.loopers.support.cache.CacheVersions versions;
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager tm;
    @MockitoSpyBean LikeUseCase likes;
    @Autowired BrandUseCase brands;
    @Autowired OrderUseCase orders;
    @MockitoSpyBean ProductUseCase products;
    @Autowired @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis;
    final ObjectMapper json = new ObjectMapper();
    CommerceFixture fixture;
    Brand brandA;
    Brand brandB;
    Product a;
    Product a2;
    Product b;
    User alice;
    User bob;

    @BeforeEach
    void setup() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        fixture = new CommerceFixture(em, tm);
        brandA = fixture.brand("brand-A");
        brandB = fixture.brand("brand-B");
        a = fixture.product(brandA, "product-A", 100L, 1);
        b = fixture.product(brandB, "product-B", 200L, 2);
        a2 = fixture.product(brandA, "product-A2", 150L, 3);
        alice = fixture.userWithPoint(1000L);
        bob = fixture.user();
        likes.register(alice.getId(), a.getId());
        likes.register(bob.getId(), b.getId());
    }

    @AfterEach
    void cleanup() { fixture.truncateRemainingTables(); }

    /** 상품 A(100원), A2(150원), B(200원)를 캐시한 뒤 A를 250원으로 수정한다. 가격순은 A2→B→A가 되고 B의 값은 유지된다. */
    @Test
    @DisplayName("상품 수정: 이름·가격·가격순을 갱신하고 다른 브랜드 상품 값은 유지한다")
    void productUpdateRefreshesExactFieldsAndPriceOrder() throws Exception {
        var before = warm();
        assertThat(ids(before.get("price_asc-all").data().path("content"))).containsExactly(a.getId(), a2.getId(), b.getId());
        write(put("/api-admin/v1/products/" + a.getId()).content("{\"name\":\"changed-A\",\"price\":250}"));
        var after = assertScope(before, Set.of(CacheKeys.CATALOG_GROUP), Set.of());
        for (String route : List.of("latest-all", "price_asc-all", "likes_desc-all", "latest-A", "price_asc-A", "likes_desc-A", "my-alice")) {
            JsonNode changed = item(after.get(route).path("content"), a.getId());
            assertThat(changed.path("name").asText()).as(route).isEqualTo("changed-A");
            assertThat(changed.path("price").asLong()).as(route).isEqualTo(250);
        }
        assertThat(after.get("detail-A").path("name").asText()).isEqualTo("changed-A");
        assertThat(after.get("detail-A").path("price").asLong()).isEqualTo(250);
        assertThat(ids(after.get("price_asc-all").path("content"))).containsExactly(a2.getId(), b.getId(), a.getId());
        assertThat(ids(after.get("price_asc-page0").path("content"))).containsExactly(a2.getId());
        assertThat(ids(after.get("price_asc-page1").path("content"))).containsExactly(b.getId());
        assertOtherBrandUnchanged(before, after);
    }

    /** A 브랜드에 상품을 생성한다. 전체·A 목록은 늘어나지만 B 목록과 기존 상품의 숫자·재고 값은 유지된다. */
    @Test
    @DisplayName("상품 생성: 전체·해당 브랜드 목록에 추가하고 다른 브랜드 목록은 유지한다")
    void productCreateRefreshesCatalogAndStock() throws Exception {
        var before = warm();
        JsonNode created = write(post("/api-admin/v1/products").content("{\"brandId\":" + brandA.getId() + ",\"name\":\"new-A\",\"price\":50}"));
        long id = created.path("id").asLong();
        var after = assertScope(before, Set.of(CacheKeys.CATALOG_GROUP), Set.of());
        for (String sort : List.of("latest", "price_asc", "likes_desc")) {
            assertThat(ids(after.get(sort + "-all").path("content"))).containsExactlyInAnyOrder(a.getId(), a2.getId(), b.getId(), id);
            assertThat(ids(after.get(sort + "-A").path("content"))).containsExactlyInAnyOrder(a.getId(), a2.getId(), id);
            assertThat(after.get(sort + "-B")).isEqualTo(before.get(sort + "-B").data());
        }
        assertThat(after.get("counts")).isEqualTo(before.get("counts").data());
        assertThat(after.get("stocks")).isEqualTo(before.get("stocks").data());
    }

    /** A 상품만 삭제한다. 같은 브랜드의 A2와 다른 브랜드의 B는 고객 응답에 남는다. */
    @Test
    @DisplayName("상품 삭제: 해당 상품만 목록·상세·내 좋아요·숫자·재고에서 제외한다")
    void productDeletionDoesNotRemoveOtherProducts() throws Exception {
        var before = warm();
        write(delete("/api-admin/v1/products/" + a.getId()));
        var after = assertScope(before, Set.of(CacheKeys.CATALOG_GROUP), Set.of("detail-A"));
        assertVisibleProducts(after, List.of(a2.getId(), b.getId()), List.of(a2.getId()));
        assertThat(ids(after.get("my-alice").path("content"))).isEmpty();
        assertOtherBrandUnchanged(before, after);
    }

    /** A 브랜드의 이름만 바꾼다. 소속 상품과 브랜드 목록에는 새 이름, B 관련 응답에는 기존 값이 남는다. */
    @Test
    @DisplayName("브랜드 이름 수정: 소속 상품과 고객·관리자 브랜드 목록을 갱신한다")
    void brandUpdateRefreshesDependentViews() throws Exception {
        var before = warm();
        write(put("/api-admin/v1/brands/" + brandA.getId()).content("{\"name\":\"renamed-A\"}"));
        var after = assertScope(before, Set.of(CacheKeys.CATALOG_GROUP), Set.of());
        for (String route : List.of("latest-all", "price_asc-all", "likes_desc-all", "latest-A", "price_asc-A", "likes_desc-A", "my-alice")) {
            assertThat(item(after.get(route).path("content"), a.getId()).path("brand").path("name").asText()).as(route).isEqualTo("renamed-A");
        }
        for (String route : List.of("detail-A", "detail-A2")) assertThat(after.get(route).path("brand").path("name").asText()).isEqualTo("renamed-A");
        for (String route : List.of("brands-customer", "brands-admin")) assertThat(item(after.get(route).path("content"), brandA.getId()).path("name").asText()).isEqualTo("renamed-A");
        assertOtherBrandUnchanged(before, after);
    }

    /** 새 브랜드 C를 만든다. 고객·관리자 브랜드 목록에 C가 추가되고 기존 상품 응답은 바뀌지 않는다. */
    @Test
    @DisplayName("브랜드 생성: 고객·관리자 브랜드 목록에 추가하되 기존 상품 응답은 유지한다")
    void brandCreateRefreshesBrandLists() throws Exception {
        var before = warm();
        JsonNode created = write(post("/api-admin/v1/brands").content("{\"name\":\"brand-C\"}"));
        var after = assertScope(before, Set.of(CacheKeys.CATALOG_GROUP), Set.of());
        for (String route : List.of("brands-customer", "brands-admin")) {
            assertThat(ids(after.get(route).path("content"))).containsExactlyInAnyOrder(brandA.getId(), brandB.getId(), created.path("id").asLong());
            assertThat(after.get(route).path("totalElements").asLong()).isEqualTo(3);
        }
        for (String route : before.keySet()) if (!route.startsWith("brands-")) assertThat(after.get(route)).as(route).isEqualTo(before.get(route).data());
    }

    /** A 브랜드를 삭제한다. A·A2는 고객 응답에서 빠지고 B는 남으며 관리자 목록에는 삭제된 A 브랜드가 남는다. */
    @Test
    @DisplayName("브랜드 삭제: 소속 상품 전체만 제외하고 다른 브랜드·상품은 유지한다")
    void brandDeletionDoesNotRemoveAnotherBrandsProducts() throws Exception {
        var before = warm();
        write(delete("/api-admin/v1/brands/" + brandA.getId()));
        var after = assertScope(before, Set.of(CacheKeys.CATALOG_GROUP), Set.of("detail-A", "detail-A2"));
        assertVisibleProducts(after, List.of(b.getId()), List.of());
        assertThat(ids(after.get("my-alice").path("content"))).isEmpty();
        assertThat(ids(after.get("brands-customer").path("content"))).containsExactly(brandB.getId());
        assertThat(after.get("brands-customer").path("totalElements").asLong()).isEqualTo(1);
        assertThat(item(after.get("brands-admin").path("content"), brandA.getId()).path("deleted").asBoolean()).isTrue();
        assertThat(after.get("brands-admin").path("totalElements").asLong()).isEqualTo(2);
        assertOtherBrandUnchanged(before, after);
    }

    /** A의 재고를 1→0으로 바꾼다. A만 품절이 되고 재고 외 캐시의 키·응답·남은 TTL은 유지된다. */
    @Test
    @DisplayName("관리자 재고 변경: 재고 캐시만 갱신하고 상품 정보·좋아요 캐시는 유지한다")
    void stockWriteLeavesOtherCachesValid() throws Exception {
        var before = warm();
        write(put("/api-admin/v1/products/" + a.getId() + "/stock").content("{\"quantity\":0}"));
        var after = assertScope(before, Set.of(CacheKeys.stockGroup(a.getId())), Set.of());
        assertThat(batchItem(after.get("stocks"), a.getId()).path("soldOut").asBoolean()).isTrue();
        assertThat(after.get("detail-A").path("soldOut").asBoolean()).isTrue();
        assertThat(item(after.get("my-alice").path("content"), a.getId()).path("soldOut").asBoolean()).isTrue();
        assertThat(item(after.get("latest-all").path("content"), a.getId()).path("soldOut").asBoolean()).isTrue();
        assertThat(batchItem(after.get("stocks"), b.getId())).isEqualTo(batchItem(before.get("stocks").data(), b.getId()));
        assertThat(batchItem(after.get("stocks"), a2.getId())).isEqualTo(batchItem(before.get("stocks").data(), a2.getId()));
    }

    /** 수량은 변해도 판매 가능 상태가 같으면 모든 표시 캐시와 revision을 재사용한다. */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"100,99", "1,100", "1,1", "0,0"})
    @DisplayName("관리자 수량 변경: 품절 상태가 같으면 revision·응답·TTL 유지")
    void unchangedAvailabilityKeepsCaches(int initial, int quantity) throws Exception {
        products.changeStock(a.getId(), initial);
        var before = warm();
        products.changeStock(a.getId(), quantity);
        assertScope(before, Set.of(), Set.of());
        assertStock(a.getId(), quantity);
    }

    /** 품절 상품을 재입고하면 A의 재고 표시만 즉시 갱신한다. */
    @Test
    @DisplayName("재입고 0→10: 해당 상품 revision 갱신·품절 해제")
    void restockChangesAvailability() throws Exception {
        products.changeStock(a.getId(), 0);
        var before = warm();
        products.changeStock(a.getId(), 10);
        var after = assertScope(before, Set.of(CacheKeys.stockGroup(a.getId())), Set.of());
        assertThat(batchItem(after.get("stocks"), a.getId()).path("soldOut").asBoolean()).isFalse();
        assertStock(a.getId(), 10);
    }

    /** 주문 금액·재고는 실제 DB에서 변경하되, 품절되지 않은 구매는 캐시를 바꾸지 않는다. */
    @Test
    @DisplayName("구매 100→99: 주문·재고·포인트 처리 후 표시 캐시는 유지")
    void purchaseWithoutSelloutKeepsCaches() throws Exception {
        products.changeStock(a.getId(), 100);
        var order = orders.create(alice.getId(), List.of(new OrderUseCase.ItemCommand(a.getId(), 1)));
        var before = warm();
        orders.confirm(alice.getId(), order.getId());
        assertScope(before, Set.of(), Set.of());
        assertStock(a.getId(), 99);
        assertBalance(alice, 900);
    }

    /** rollback된 상태는 버리지 않고 다음 트랜잭션에서도 다시 품절 전환을 감지한다. */
    @Test
    @DisplayName("품절 변경 rollback: 이전 캐시 유지, 다음 정상 변경은 갱신")
    void availabilityRollbackDoesNotLeakState() throws Exception {
        var before = warm();
        new TransactionTemplate(tm).executeWithoutResult(tx -> {
            products.changeStock(a.getId(), 0);
            tx.setRollbackOnly();
        });
        assertScope(before, Set.of(), Set.of());
        assertStock(a.getId(), 1);
        products.changeStock(a.getId(), 0);
        assertScope(before, Set.of(CacheKeys.stockGroup(a.getId())), Set.of());
        assertStock(a.getId(), 0);
    }

    /** A 1개 주문을 확정한다. 재고만 갱신되며 catalog와 두 사용자의 좋아요 캐시는 유지된다. */
    @Test
    @DisplayName("주문 확정: 재고 캐시만 갱신하고 다른 조회 캐시는 유지한다")
    void orderConfirmationOnlyRefreshesStock() throws Exception {
        var order = orders.create(alice.getId(), List.of(new OrderUseCase.ItemCommand(a.getId(), 1)));
        var before = warm();
        orders.confirm(alice.getId(), order.getId());
        var after = assertScope(before, Set.of(CacheKeys.stockGroup(a.getId())), Set.of());
        assertThat(batchItem(after.get("stocks"), a.getId()).path("soldOut").asBoolean()).isTrue();
        assertThat(after.get("detail-A").path("soldOut").asBoolean()).isTrue();
        assertThat(item(after.get("my-alice").path("content"), a.getId()).path("soldOut").asBoolean()).isTrue();
        assertThat(item(after.get("latest-all").path("content"), a.getId()).path("soldOut").asBoolean()).isTrue();
        assertThat(batchItem(after.get("stocks"), b.getId())).isEqualTo(batchItem(before.get("stocks").data(), b.getId()));
    }

    /** Alice가 B를 좋아요한다. Alice 목록만 즉시 추가되고 Bob 목록·숫자·순위는 기존 캐시를 쓴다. 만료 후 B의 숫자는 2, 순위는 1위다. */
    @Test
    @DisplayName("좋아요 등록: 본인 목록만 즉시 추가하고 숫자·순위는 만료 후 갱신한다")
    void registeringLikeOnlyRefreshesOwnersListImmediately() throws Exception {
        var before = warm();
        customerWrite(post("/api/v1/products/" + b.getId() + "/likes"));
        var after = assertScope(before, Set.of(CacheKeys.userGroup(alice.getId())), Set.of());
        assertThat(ids(after.get("my-alice").path("content"))).containsExactlyInAnyOrder(a.getId(), b.getId());
        assertThat(after.get("my-alice").path("totalElements").asLong()).isEqualTo(2);
        JsonNode counts = expireAndRead(before.get("counts").route());
        assertThat(batchItem(counts, b.getId()).path("likeCount").asLong()).isEqualTo(2);
        JsonNode ranking = expireAndRead(before.get("likes_desc-all").route());
        assertThat(ids(ranking.path("content"))).containsExactly(b.getId(), a.getId(), a2.getId());
    }

    /** Alice가 A의 좋아요를 취소한다. Alice 목록만 즉시 비고 Bob 목록은 유지된다. 숫자·순위는 만료 후 반영된다. */
    @Test
    @DisplayName("좋아요 취소: 본인 목록만 즉시 제거하고 다른 사용자·숫자·순위 캐시는 유지한다")
    void cancellingLikeOnlyRefreshesOwnersListImmediately() throws Exception {
        var before = warm();
        customerWrite(delete("/api/v1/products/" + a.getId() + "/likes"));
        var after = assertScope(before, Set.of(CacheKeys.userGroup(alice.getId())), Set.of());
        assertThat(ids(after.get("my-alice").path("content"))).isEmpty();
        assertThat(after.get("my-alice").path("totalElements").asLong()).isZero();
        JsonNode counts = expireAndRead(before.get("counts").route());
        assertThat(batchItem(counts, a.getId()).path("likeCount").asLong()).isZero();
        JsonNode ranking = expireAndRead(before.get("likes_desc-all").route());
        assertThat(ids(ranking.path("content"))).containsExactly(b.getId(), a.getId(), a2.getId());
    }

    @ParameterizedTest(name = "실제 변경 없는 요청: {0}")
    @ValueSource(strings = {"duplicate-register", "cancel-absent"})
    @DisplayName("중복 좋아요 등록·없는 좋아요 취소는 모든 캐시 버전과 응답을 유지한다")
    void noOpLikeWriteDoesNotInvalidateCaches(String action) throws Exception {
        var before = warm();
        if (action.equals("duplicate-register")) customerWrite(post("/api/v1/products/" + a.getId() + "/likes"));
        else customerWrite(delete("/api/v1/products/" + b.getId() + "/likes"));
        assertScope(before, Set.of(), Set.of());
    }

    /** A의 이름·가격을 수정한 트랜잭션을 롤백한다. 캐시 응답뿐 아니라 DB 이름·가격·각 그룹 revision도 원래 값이어야 한다. */
    @Test
    @DisplayName("상품 수정 롤백: DB 이름·가격·revision과 기존 캐시가 모두 유지된다")
    void rollbackPreservesDatabaseRevisionAndAllCachedViews() throws Exception {
        var before = warm();
        new TransactionTemplate(tm).executeWithoutResult(tx -> {
            products.update(a.getId(), "rolled-back", 999L, null);
            tx.setRollbackOnly();
        });
        assertScope(before, Set.of(), Set.of());
        var actual = products.findBasicProduct(a.getId()).product();
        assertThat(actual.getName()).isEqualTo("product-A");
        assertThat(actual.getPrice()).isEqualTo(100L);
    }

    /**
     * 실제 상세 HTTP 요청을 DB 읽기 직후 멈춘다. 별도 요청으로 수정/삭제 커밋 후 읽기를 재개한다.
     * 진행 중이던 요청의 옛 응답은 허용하지만, 이후 새 요청은 그 응답을 재사용하면 안 된다.
     */
    @ParameterizedTest(name = "늦은 조회 재개 전 쓰기: {0}")
    @ValueSource(strings = {"update", "delete"})
    @DisplayName("진행 중인 옛 조회가 뒤늦게 저장해도 커밋 후 새 조회에 사용되지 않는다")
    void delayedRealHttpReadCannotRepopulateCurrentVersion(String action) throws Exception {
        Route detail = routes().get("detail-A");
        String oldKey = cacheKey(detail);
        CountDownLatch readCompleted = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            readCompleted.countDown();
            if (!resume.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("writer did not release reader");
            return result;
        }).when(products).findBasicProduct(a.getId());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var oldRead = executor.submit(() -> read(detail, 200));
            assertThat(readCompleted.await(10, TimeUnit.SECONDS)).isTrue();
            if (action.equals("update")) write(put("/api-admin/v1/products/" + a.getId()).content("{\"name\":\"after-commit\",\"price\":300}"));
            else write(delete("/api-admin/v1/products/" + a.getId()));
            assertThat(cacheKey(detail)).isNotEqualTo(oldKey);
            resume.countDown();
            assertThat(oldRead.get(10, TimeUnit.SECONDS).path("name").asText()).isEqualTo("product-A");
            assertThat(json.readTree(redis.opsForValue().get(oldKey)).path("data").path("name").asText()).isEqualTo("product-A");
            if (action.equals("update")) assertThat(read(detail, 200).path("name").asText()).isEqualTo("after-commit");
            else read(detail, 404);
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 본인 목록의 옛 읽기를 멈춘 뒤 취소를 커밋하고, 옛 키가 채워져도 타인 접근과 최신 본인 조회를 보호한다. */
    @Test
    @org.junit.jupiter.api.Timeout(60)
    @DisplayName("[R-LIKE-04/R-ORDER-13/R-POINT-02] 늦은 본인 목록 채움 전후에도 타인 조회·주문 확정을 거절한다")
    void delayedPrivateListNeverBypassesOwnership() throws Exception {
        Route mine = routes().get("my-alice");
        String oldKey = cacheKey(mine);
        long orderId = createHttpOrder(alice, a.getId());
        JsonNode beforeOrder = customerData(get("/api/v1/orders/" + orderId), alice);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            loaded.countDown();
            if (!resume.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("private read was not released");
            return result;
        }).when(likes).findMineBasicPage(alice.getId(), alice.getId(), 0, 20);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var oldRead = executor.submit(() -> read(mine, 200));
            assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
            customerWrite(delete("/api/v1/products/" + a.getId() + "/likes"));
            assertThat(cacheKey(mine)).isNotEqualTo(oldKey);
            for (boolean afterFill : List.of(false, true)) {
                if (afterFill) {
                    resume.countDown();
                    assertThat(ids(oldRead.get(10, TimeUnit.SECONDS).path("content"))).containsExactly(a.getId());
                    assertThat(ids(json.readTree(redis.opsForValue().get(oldKey)).path("data").path("content"))).containsExactly(a.getId());
                }
                reject(get(mine.url()), bob, org.springframework.http.HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
                reject(get("/api/v1/orders/" + orderId), bob, org.springframework.http.HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
                reject(post("/api/v1/orders/" + orderId + "/confirm"), bob, org.springframework.http.HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
                mvc.perform(get(mine.url())).andExpect(com.loopers.support.http.ApiHttp.failure(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "USER_NOT_IDENTIFIED"));
                assertThat(customerData(get("/api/v1/orders/" + orderId), alice)).isEqualTo(beforeOrder);
                assertBalance(alice, 1000); assertBalance(bob, 0);
                assertStock(a.getId(), 1);
                assertThat(customerData(get("/api/v1/orders"), bob).path("content").size()).isZero();
                assertThat(ids(read(routes().get("my-bob"), 200).path("content"))).containsExactly(b.getId());
            }
            assertThat(ids(read(mine, 200).path("content"))).isEmpty();
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 상품/재고의 옛 DB 결과 → 관리자 commit → 옛 캐시 저장 순서를 고정한 뒤 실제 주문 HTTP를 실행한다. */
    @ParameterizedTest(name = "늦은 채움과 주문 판단: {0}")
    @ValueSource(strings = {"price", "stock", "delete"})
    @org.junit.jupiter.api.Timeout(60)
    @DisplayName("[R-ORDER-02/05/07/08/09] 늦게 저장된 상품·재고 표시가 주문 가격·삭제·재고·포인트 판단을 바꾸지 않는다")
    void delayedDisplayFillCannotChangeOrderDecisions(String change) throws Exception {
        Route route = routes().get(change.equals("stock") ? "stocks" : "detail-A");
        String oldKey = cacheKey(route);
        long existingOrder = createHttpOrder(alice, a.getId());
        JsonNode originalOrder = customerData(get("/api/v1/orders/" + existingOrder), alice);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        org.mockito.stubbing.Answer<Object> pauseAfterDatabaseRead = invocation -> {
            Object result = invocation.callRealMethod();
            loaded.countDown();
            if (!resume.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("display read was not released");
            return result;
        };
        if (change.equals("stock")) {
            doAnswer(pauseAfterDatabaseRead).when(products).findStockStatuses(
                List.of(a.getId(), a2.getId(), b.getId()).stream().sorted().toList());
        } else {
            doAnswer(pauseAfterDatabaseRead).when(products).findBasicProduct(a.getId());
        }
        var executor = Executors.newSingleThreadExecutor();
        try {
            var oldRead = executor.submit(() -> read(route, 200));
            assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
            switch (change) {
                case "price" -> write(put("/api-admin/v1/products/" + a.getId()).content("{\"name\":\"new-price\",\"price\":400}"));
                case "stock" -> write(put("/api-admin/v1/products/" + a.getId() + "/stock").content("{\"quantity\":0}"));
                case "delete" -> write(delete("/api-admin/v1/products/" + a.getId()));
                default -> throw new IllegalArgumentException(change);
            }
            assertThat(cacheKey(route)).isNotEqualTo(oldKey);
            resume.countDown();
            JsonNode old = oldRead.get(10, TimeUnit.SECONDS);
            assertThat(route.bucket().equals(CacheKeys.PRODUCTS) ? json.readTree(redis.opsForValue().get(oldKey)).path("data")
                : json.readTree(redis.opsForValue().get(oldKey))).isEqualTo(
                route.bucket().equals(CacheKeys.PRODUCTS) ? basePayload(old) : batchItem(old, a.getId()));
            if (change.equals("stock")) assertThat(batchItem(old, a.getId()).path("soldOut").asBoolean()).isFalse();
            else assertThat(old.path("price").asLong()).isEqualTo(100);

            if (change.equals("price")) {
                assertThat(read(route, 200).path("price").asLong()).isEqualTo(400);
                var poor = fixture.userWithPoint(1);
                long poorOrder = createHttpOrder(poor, a.getId());
                JsonNode poorBefore = customerData(get("/api/v1/orders/" + poorOrder), poor);
                reject(post("/api/v1/orders/" + poorOrder + "/confirm"), poor,
                    org.springframework.http.HttpStatus.CONFLICT, "INSUFFICIENT_POINT");
                assertThat(customerData(get("/api/v1/orders/" + poorOrder), poor)).isEqualTo(poorBefore);
                assertBalance(poor, 1); assertBalance(bob, 0); assertBalance(alice, 1000); assertStock(a.getId(), 1);
                long currentOrder = createHttpOrder(alice, a.getId());
                assertThat(customerData(get("/api/v1/orders/" + currentOrder), alice).path("totalAmount").asLong()).isEqualTo(400);
                write(put("/api-admin/v1/products/" + a.getId()).content("{\"name\":\"later-price\",\"price\":900}"));
                JsonNode confirmed = customerData(post("/api/v1/orders/" + currentOrder + "/confirm"), alice);
                assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");
                assertThat(confirmed.path("items").get(0).path("unitPrice").asLong()).isEqualTo(400);
                assertThat(confirmed.path("payment").path("amount").asLong()).isEqualTo(400);
                assertBalance(alice, 600); assertStock(a.getId(), 0);
            } else {
                if (change.equals("delete")) {
                    read(route, 404);
                    reject(post("/api/v1/orders").content(orderItems(a.getId())), alice,
                        org.springframework.http.HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
                } else {
                    assertThat(batchItem(read(route, 200), a.getId()).path("soldOut").asBoolean()).isTrue();
                }
                reject(post("/api/v1/orders/" + existingOrder + "/confirm"), alice,
                    org.springframework.http.HttpStatus.CONFLICT, change.equals("delete") ? "PRODUCT_NOT_AVAILABLE" : "INSUFFICIENT_STOCK");
                assertBalance(alice, 1000); assertBalance(bob, 0);
                assertStock(a.getId(), change.equals("stock") ? 0 : 1);
                assertThat(customerData(get("/api/v1/orders"), alice).path("content").size()).isEqualTo(1);
            }
            assertThat(customerData(get("/api/v1/orders/" + existingOrder), alice)).isEqualTo(originalOrder);
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    JsonNode customerData(MockHttpServletRequestBuilder request, User user) throws Exception {
        return com.loopers.support.http.ApiHttp.data(mvc.perform(request.with(customer(user)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)).andExpect(status().is2xxSuccessful()).andReturn());
    }
    long createHttpOrder(User user, Long productId) throws Exception {
        return customerData(post("/api/v1/orders").content(orderItems(productId)), user).path("id").asLong();
    }
    String orderItems(Long productId) { return "{\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}"; }
    void reject(MockHttpServletRequestBuilder request, User user, org.springframework.http.HttpStatus status, String code) throws Exception {
        mvc.perform(request.with(customer(user)).with(csrf()).contentType(MediaType.APPLICATION_JSON))
            .andExpect(com.loopers.support.http.ApiHttp.failure(status, code));
    }
    void assertBalance(User user, long expected) throws Exception {
        assertThat(customerData(get("/api/v1/points"), user).path("balance").asLong()).isEqualTo(expected);
    }
    void assertStock(Long productId, int expected) {
        Integer quantity = new TransactionTemplate(tm).execute(tx -> (Integer) em.createNativeQuery(
            "select quantity from stock where product_id = :id", Integer.class).setParameter("id", productId).getSingleResult());
        assertThat(quantity).isEqualTo(expected);
    }

    // 아래 도우미는 쓰기 전에 관련·무관한 조회를 모두 캐시하고, 쓰기 후 키·응답·TTL을 비교한다.
    @Autowired com.loopers.product.interfaces.ProductReadFacade reads;

    record Route(String url, String bucket, String apiKey, User user, List<String> groups) { }
    record Cached(Route route, String key, JsonNode data) { }

    Map<String, Route> routes() {
        Map<String, Route> routes = new LinkedHashMap<>();
        for (String sort : List.of("latest", "price_asc", "likes_desc")) {
            for (String filter : List.of("all", "A", "B")) {
                Long brand = filter.equals("all") ? null : filter.equals("A") ? brandA.getId() : brandB.getId();
                String url = "/api/v1/products?sort=" + sort + (brand == null ? "" : "&brandId=" + brand);
                routes.put(sort + "-" + filter, new Route(url, sort.equals("likes_desc") ? CacheKeys.RANKINGS : CacheKeys.PRODUCTS,
                    CacheKeys.products(sort, brand, 0, 20), alice, List.of(CacheKeys.CATALOG_GROUP)));
            }
        }
        for (int page : List.of(0, 1)) routes.put("price_asc-page" + page, new Route("/api/v1/products?sort=price_asc&size=1&page=" + page,
            CacheKeys.PRODUCTS, CacheKeys.products("price_asc", null, page, 1), alice, List.of(CacheKeys.CATALOG_GROUP)));
        for (var entry : Map.of("A", a, "A2", a2, "B", b).entrySet()) routes.put("detail-" + entry.getKey(), new Route(
            "/api/v1/products/" + entry.getValue().getId(), CacheKeys.PRODUCTS, CacheKeys.product(entry.getValue().getId()), alice, List.of(CacheKeys.CATALOG_GROUP)));
        for (var entry : Map.of("alice", alice, "bob", bob).entrySet()) routes.put("my-" + entry.getKey(), new Route(
            "/api/v1/users/" + entry.getValue().getId() + "/likes", CacheKeys.MY_LIKES, CacheKeys.myLikes(entry.getValue().getId(), 0, 20), entry.getValue(),
            List.of(CacheKeys.CATALOG_GROUP, CacheKeys.userGroup(entry.getValue().getId()))));
        routes.put("brands-customer", new Route("/api/v1/brands", CacheKeys.BRANDS, CacheKeys.customerBrands(0, 20), alice, List.of(CacheKeys.CATALOG_GROUP)));
        routes.put("brands-admin", new Route("/api-admin/v1/brands", CacheKeys.BRANDS, CacheKeys.brands(0, 20), null, List.of(CacheKeys.CATALOG_GROUP)));
        List<Long> ids = List.of(a.getId(), a2.getId(), b.getId()).stream().sorted().toList();
        String query = ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        for (String bucket : List.of(CacheKeys.COUNTS, CacheKeys.STOCKS)) routes.put(bucket.equals(CacheKeys.COUNTS) ? "counts" : "stocks",
            new Route("internal:" + bucket + "?ids=" + query, bucket, CacheKeys.item(bucket, a.getId()), alice,
                bucket.equals(CacheKeys.STOCKS) ? List.of(CacheKeys.CATALOG_GROUP, CacheKeys.stockGroup(a.getId())) : List.of(CacheKeys.CATALOG_GROUP)));
        return routes;
    }

    Map<String, Cached> warm() throws Exception {
        Map<String, Cached> cached = new LinkedHashMap<>();
        for (var entry : routes().entrySet()) {
            JsonNode data = read(entry.getValue(), 200);
            String key = cacheKey(entry.getValue());
            assertThat(redis.hasKey(key)).as(entry.getKey()).isTrue();
            redis.expire(key, Duration.ofSeconds(120));
            cached.put(entry.getKey(), new Cached(entry.getValue(), key, data));
        }
        return cached;
    }

    Map<String, JsonNode> assertScope(Map<String, Cached> before, Set<String> changedGroups, Set<String> notFound) throws Exception {
        // Verify revision changes before any route can refill the new keys.
        for (var entry : before.entrySet()) {
            boolean changed = entry.getValue().route().groups().stream().anyMatch(changedGroups::contains);
            assertThat(cacheKey(entry.getValue().route()).equals(entry.getValue().key())).as(entry.getKey() + " revision scope").isEqualTo(!changed);
            assertThat(redis.hasKey(entry.getValue().key())).isTrue();
        }
        Map<String, JsonNode> after = new LinkedHashMap<>();
        for (var entry : before.entrySet()) {
            String name = entry.getKey();
            Cached old = entry.getValue();
            String currentKey = cacheKey(old.route());
            boolean changed = old.route().groups().stream().anyMatch(changedGroups::contains);
            if (changed) assertThat(currentKey).as(name + " 새 revision 키").isNotEqualTo(old.key());
            else assertThat(currentKey).isEqualTo(old.key());
            JsonNode data = read(old.route(), notFound.contains(name) ? 404 : 200);
            after.put(name, data);
            if (!changed) {
                if (changedGroups.contains(CacheKeys.stockGroup(a.getId()))) {
                    assertThat(withoutStock(data)).as(name + " 재고 외 응답 유지").isEqualTo(withoutStock(old.data()));
                } else {
                    assertThat(data).as(name + " 유지 대상 응답").isEqualTo(old.data());
                }
                assertThat(redis.getExpire(currentKey)).as(name + " 유지 대상 TTL").isPositive().isLessThanOrEqualTo(120L);
            } else if (!notFound.contains(name)) {
                boolean missingItem = (old.route().bucket().equals(CacheKeys.COUNTS) || old.route().bucket().equals(CacheKeys.STOCKS))
                    && java.util.stream.StreamSupport.stream(data.spliterator(), false).noneMatch(item -> item.path("productId").asLong() == a.getId());
                if (missingItem) assertThat(redis.hasKey(currentKey)).as(name + " 삭제 상품은 개별 캐시에 저장하지 않음").isFalse();
                else assertThat(redis.getExpire(currentKey)).as(name + " 새 키 TTL").isBetween(269L, 330L);
            }

        }
        return after;
    }

    String cacheKey(Route route) { return route.bucket() + "::" + versions.key(route.apiKey(), route.groups().toArray(String[]::new)); }
    // 배치 API는 폐지했다. 고정 ID 집합의 무효화 범위는 내부 캐시에서 검증한다.
    // 목록·상세·내 좋아요와 변경 요청은 실제 HTTP 경로로 검증한다.
    JsonNode read(Route route, int expectedStatus) throws Exception {
        if (route.bucket().equals(CacheKeys.COUNTS) || route.bucket().equals(CacheKeys.STOCKS)) {
            assertThat(expectedStatus).isEqualTo(200);
            var ids = List.of(a.getId(), a2.getId(), b.getId());
            return json.readTree(json.writeValueAsString(route.bucket().equals(CacheKeys.COUNTS) ? reads.counts(ids).data() : reads.stocks(ids).data()));
        }
        var result = mvc.perform(get(route.url()).with(route.user() == null ? admin() : customer(route.user())))
            .andExpect(status().is(expectedStatus)).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }
    JsonNode withoutStock(JsonNode data) {
        JsonNode copy = data.deepCopy();
        if (copy.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) copy).remove("soldOut");
            copy.path("content").forEach(item -> ((com.fasterxml.jackson.databind.node.ObjectNode) item).remove("soldOut"));
        }
        return copy;
    }
    JsonNode basePayload(JsonNode data) {
        JsonNode copy = data.deepCopy();
        if (copy.isObject()) {
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) copy;
            object.remove(List.of("likeCount", "soldOut"));
            if (object.has("content")) {
                object.path("content").forEach(item -> ((com.fasterxml.jackson.databind.node.ObjectNode) item).remove(List.of("likeCount", "soldOut")));
            }
        }
        return copy;
    }
    JsonNode write(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request.with(admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString()).path("data");
    }
    void customerWrite(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.with(customer(alice)).with(csrf())).andExpect(status().is2xxSuccessful());
    }
    JsonNode expireAndRead(Route route) throws Exception {
        String key = cacheKey(route);
        redis.expire(key, Duration.ofMillis(1));
        if (route.bucket().equals(CacheKeys.COUNTS) || route.bucket().equals(CacheKeys.STOCKS)) {
            for (Long id : List.of(a.getId(), a2.getId(), b.getId())) {
                String[] groups = route.bucket().equals(CacheKeys.STOCKS)
                    ? new String[]{CacheKeys.CATALOG_GROUP, CacheKeys.stockGroup(id)} : new String[]{CacheKeys.CATALOG_GROUP};
                redis.expire(route.bucket() + "::" + versions.key(CacheKeys.item(route.bucket(), id), groups), Duration.ofMillis(1));
            }
        }
        Thread.sleep(10);
        assertThat(redis.hasKey(key)).isFalse();
        return read(route, 200);
    }
    List<Long> ids(JsonNode content) {
        List<Long> result = new ArrayList<>();
        content.forEach(item -> result.add(item.path("id").asLong()));
        return result;
    }
    JsonNode item(JsonNode content, long id) {
        for (JsonNode item : content) if (item.path("id").asLong() == id) return item;
        throw new AssertionError("응답에 상품/브랜드가 없음: " + id + " " + content);
    }
    JsonNode batchItem(JsonNode content, long id) {
        for (JsonNode item : content) if (item.path("productId").asLong() == id) return item;
        throw new AssertionError("일괄 응답에 상품이 없음: " + id + " " + content);
    }
    void assertOtherBrandUnchanged(Map<String, Cached> before, Map<String, JsonNode> after) {
        for (String route : List.of("detail-B", "latest-B", "price_asc-B", "likes_desc-B", "my-bob")) {
            assertThat(after.get(route)).as(route + " 다른 브랜드 응답 유지").isEqualTo(before.get(route).data());
        }
    }
    void assertVisibleProducts(Map<String, JsonNode> after, List<Long> all, List<Long> brandProducts) {
        for (String sort : List.of("latest", "price_asc", "likes_desc")) {
            assertThat(ids(after.get(sort + "-all").path("content"))).containsExactlyInAnyOrderElementsOf(all);
            assertThat(ids(after.get(sort + "-A").path("content"))).containsExactlyInAnyOrderElementsOf(brandProducts);
        }
        for (String route : List.of("counts", "stocks")) {
            List<Long> actual = new ArrayList<>();
            after.get(route).forEach(item -> actual.add(item.path("productId").asLong()));
            assertThat(actual).as(route).containsExactlyInAnyOrderElementsOf(all);
        }
    }
}
