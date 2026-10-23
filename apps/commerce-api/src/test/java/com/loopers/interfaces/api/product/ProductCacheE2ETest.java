package com.loopers.interfaces.api.product;

import com.loopers.application.payment.PaymentTransactions;
import com.loopers.domain.payment.LookupResult;
import com.loopers.domain.payment.PaymentDiscrepancy;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PgTransaction;
import com.loopers.application.brand.BrandFacade;
import com.loopers.application.like.LikeFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.product.Price;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductCacheE2ETest {

    private static final String CUSTOMER = "/api/v1/products";
    private static final String PAGE_KEYS = "product:list:v1:brand=*";
    private static final Long USER = 1L;
    private static final Long OTHER_USER = 2L;
    private static final String ADMIN_NAME = "admin";
    private static final String ADMIN_PASSWORD = "local-only-not-a-secret";

    private static final ParameterizedTypeReference<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> PAGE =
        new ParameterizedTypeReference<>() {};

    private final TestRestTemplate testRestTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final RedisTemplate<String, String> redisTemplate;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;
    private final LikeFacade likeFacade;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final PaymentTransactions paymentTransactions;
    private final PaymentRepository paymentRepository;

    private Long brandId;

    @Autowired
    ProductCacheE2ETest(
        TestRestTemplate testRestTemplate,
        JdbcTemplate jdbcTemplate,
        RedisTemplate<String, String> redisTemplate,
        DatabaseCleanUp databaseCleanUp,
        RedisCleanUp redisCleanUp,
        BrandFacade brandFacade,
        ProductFacade productFacade,
        LikeFacade likeFacade,
        PlatformTransactionManager transactionManager,
        ObjectMapper objectMapper,
        PaymentTransactions paymentTransactions,
        PaymentRepository paymentRepository
    ) {
        this.testRestTemplate = testRestTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
        this.likeFacade = likeFacade;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.paymentTransactions = paymentTransactions;
        this.paymentRepository = paymentRepository;
    }

    @BeforeEach
    void setUp() {
        brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private Long product(String name, long price) {
        return productFacade.register(brandId, name, Price.of(price)).getId();
    }

    private PageResponse<ProductV1Dto.ProductResponse> page(Long requester, String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(requester));
        return testRestTemplate.exchange(CUSTOMER + query, HttpMethod.GET, new HttpEntity<>(headers), PAGE)
            .getBody().data();
    }

    private String firstPriceAscPage() {
        return "?brandId=" + brandId + "&sort=price_asc";
    }

    private Set<String> pageKeys() {
        return redisTemplate.keys(PAGE_KEYS);
    }

    private JsonNode admin(HttpMethod method, String path, Map<String, Object> body) {
        return call(testRestTemplate.withBasicAuth(ADMIN_NAME, ADMIN_PASSWORD), method, path, new HttpHeaders(), body);
    }

    private JsonNode customer(HttpMethod method, String path, Long requester, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(requester));
        return call(testRestTemplate, method, path, headers, body);
    }

    private JsonNode call(TestRestTemplate client, HttpMethod method, String path, HttpHeaders headers, Map<String, Object> body) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = client.exchange(path, method, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(method + " " + path + " → " + response.getBody()).isTrue();
        try {
            return response.getBody() == null ? null : objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> card(Long orderId) {
        return Map.<String, Object>of("paymentAttemptKey", "attempt-" + orderId, "cardType", "TEST", "cardNo", "0000-0000-0000-0000");
    }

    private Long confirmedOrder(Long productId, long amount) {
        productFacade.adjustStock(productId, Quantity.of(1));
        customer(HttpMethod.POST, "/api/v1/points/charge", USER, Map.of("amount", amount));
        Long orderId = customer(HttpMethod.POST, "/api/v1/orders", USER,
            Map.of("lines", List.of(Map.of("productId", productId, "quantity", 1)))).path("data").path("id").asLong();
        customer(HttpMethod.POST, "/api/v1/orders/" + orderId + "/confirm", USER, card(orderId));
        succeed(orderId);
        return orderId;
    }

    private void succeed(Long orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        paymentTransactions.observe(payment.getId(), new LookupResult.Found(List.of(new PgTransaction(
                payment.getTransactionKey(), payment.getProviderOrderId(), payment.getAmount(), PgTransaction.Status.SUCCESS))),
            PaymentDiscrepancy.Source.LOOKUP, Instant.now());
    }

    private JsonNode order(Long orderId) {
        return customer(HttpMethod.GET, "/api/v1/orders/" + orderId, USER, null).path("data");
    }

    private void changePriceBypassingApplication(Long productId, long price) {
        jdbcTemplate.update("UPDATE product SET price = ? WHERE id = ?", price, productId);
    }

    @DisplayName("CACHE-002 · 같은 요청의 miss 와 hit 가 같은 결과를 주고, miss 가 공용 페이지를 캐시에 남긴다")
    @Test
    void missAndHitReturnSameResult() {
        Long cheap = product("셔츠", 59_000);
        product("코트", 129_000);
        likeFacade.like(OTHER_USER, cheap);

        PageResponse<ProductV1Dto.ProductResponse> miss = page(USER, firstPriceAscPage());
        PageResponse<ProductV1Dto.ProductResponse> hit = page(USER, firstPriceAscPage());

        assertAll(
            () -> assertThat(hit).isEqualTo(miss),
            () -> assertThat(miss.items()).extracting(ProductV1Dto.ProductResponse::name).containsExactly("셔츠", "코트"),
            () -> assertThat(pageKeys()).hasSize(1)
        );
    }

    @DisplayName("CACHE-002 · hit 는 원본을 다시 읽지 않는다 — 응용 계층을 거치지 않은 DB 변경은 캐시가 비기 전까지 보이지 않는다")
    @Test
    void hitIsServedFromCache() {
        Long productId = product("셔츠", 59_000);
        page(USER, firstPriceAscPage());

        changePriceBypassingApplication(productId, 1_000);

        assertThat(page(USER, firstPriceAscPage()).items())
            .extracting(ProductV1Dto.ProductResponse::price).containsExactly(59_000L);
    }

    @DisplayName("CACHE-004 · liked 는 공용 캐시에 담기지 않는다 — 다른 사람이 채운 캐시를 읽어도 자기 좋아요만 보인다")
    @Test
    void likedIsComposedPerRequester() {
        Long productId = product("셔츠", 59_000);
        likeFacade.like(USER, productId);

        boolean byFiller = page(USER, firstPriceAscPage()).items().get(0).liked();
        boolean byOther = page(OTHER_USER, firstPriceAscPage()).items().get(0).liked();

        assertAll(
            () -> assertThat(byFiller).isTrue(),
            () -> assertThat(byOther).isFalse(),
            () -> assertThat(pageKeys()).hasSize(1)
        );
    }

    @DisplayName("CACHE-005 · 좋아요 수는 캐시에 담기지 않는다 — hit 에서도 좋아요는 바로 반영된다")
    @Test
    void likeCountIsReadFromSourceEvenOnHit() {
        Long productId = product("셔츠", 59_000);
        page(USER, firstPriceAscPage());
        changePriceBypassingApplication(productId, 1_000);

        customer(HttpMethod.POST, CUSTOMER + "/" + productId + "/likes", OTHER_USER, null);
        ProductV1Dto.ProductResponse item = page(USER, firstPriceAscPage()).items().get(0);

        assertAll(
            () -> assertThat(item.price()).as("가격은 캐시에서 — hit 였다").isEqualTo(59_000L),
            () -> assertThat(item.likeCount()).as("좋아요 수는 원본에서").isEqualTo(1L)
        );
    }

    @DisplayName("Q-2 · 가격순 · 최신순의 첫 페이지(크기 20)만 캐시한다. 인기순 · 다음 페이지 · 다른 크기는 원본으로 간다")
    @Test
    void cachesOnlyFirstPageOfPriceAndLatest() {
        product("셔츠", 59_000);

        page(USER, "?brandId=" + brandId + "&sort=likes_desc");
        page(USER, "?brandId=" + brandId + "&sort=price_asc&page=1");
        page(USER, "?brandId=" + brandId + "&sort=price_asc&size=10");
        Set<String> afterUncached = pageKeys();

        page(USER, "?brandId=" + brandId + "&sort=latest");
        page(USER, "?sort=price_asc");

        assertAll(
            () -> assertThat(afterUncached).isEmpty(),
            () -> assertThat(pageKeys()).hasSize(2)
        );
    }

    @DisplayName("CACHE-008 · 캐시에 남은 상품이 그사이 삭제됐으면 hit 에서도 보이지 않는다")
    @Test
    void hidesDeletedProductEvenOnHit() {
        Long deleted = product("양말", 1_000);
        Long kept = product("셔츠", 59_000);
        page(USER, firstPriceAscPage());

        jdbcTemplate.update("UPDATE product SET deleted_at = NOW(6) WHERE id = ?", deleted);

        assertThat(page(USER, firstPriceAscPage()).items())
            .extracting(ProductV1Dto.ProductResponse::id).containsExactly(kept);
    }

    @DisplayName("CACHE-006 · 관리자가 가격을 바꾸고 커밋하면 브랜드 목록과 전체 목록의 키가 지워지고, 다음 조회는 새 가격과 바뀐 순서를 준다")
    @Test
    void priceUpdateRefreshesBrandAndAllListings() {
        Long cheaper = product("셔츠", 10_000);
        Long pricier = product("코트", 20_000);
        Long pastOrder = confirmedOrder(pricier, 20_000);
        page(USER, firstPriceAscPage());
        page(USER, "?sort=price_asc");

        admin(HttpMethod.PUT, "/api-admin/v1/products/" + pricier, Map.of("name", "코트", "price", 5_000));
        Set<String> keysAfterUpdate = pageKeys();
        JsonNode order = order(pastOrder);

        assertAll(
            () -> assertThat(keysAfterUpdate).as("커밋 뒤 두 목록의 키가 지워졌다").isEmpty(),
            () -> assertThat(page(USER, firstPriceAscPage()).items())
                .extracting(ProductV1Dto.ProductResponse::id).containsExactly(pricier, cheaper),
            () -> assertThat(page(USER, "?sort=price_asc").items())
                .extracting(ProductV1Dto.ProductResponse::price).containsExactly(5_000L, 10_000L),
            () -> assertThat(order.path("totalAmount").asLong()).as("과거 주문 금액은 그대로").isEqualTo(20_000L),
            () -> assertThat(order.path("paidAmount").asLong()).isEqualTo(20_000L),
            () -> assertThat(order.path("items").get(0).path("unitPrice").asLong()).isEqualTo(20_000L)
        );
    }

    @DisplayName("CACHE-005 · 상품을 등록하면 다음 조회에 나온다")
    @Test
    void registrationRefreshesListing() {
        product("코트", 20_000);
        page(USER, firstPriceAscPage());

        Long registered = admin(HttpMethod.POST, "/api-admin/v1/products",
            Map.of("brandId", brandId, "name", "양말", "price", 1_000)).path("data").path("id").asLong();

        assertThat(page(USER, firstPriceAscPage()).items())
            .extracting(ProductV1Dto.ProductResponse::id).first().isEqualTo(registered);
    }

    @DisplayName("CACHE-005 · 상품을 삭제하면 그 브랜드와 전체 목록의 키가 지워진다 — 꺼낼 때의 재확인에만 기대지 않는다")
    @Test
    void deletionEvictsListings() {
        Long productId = product("셔츠", 10_000);
        page(USER, firstPriceAscPage());
        page(USER, "?sort=price_asc");

        admin(HttpMethod.DELETE, "/api-admin/v1/products/" + productId, null);

        assertThat(pageKeys()).isEmpty();
    }

    @DisplayName("CACHE-005 · 브랜드 이름을 바꾸면 다음 조회의 브랜드 이름이 바뀐다")
    @Test
    void brandUpdateRefreshesBrandName() {
        product("셔츠", 10_000);
        page(USER, firstPriceAscPage());

        admin(HttpMethod.PUT, "/api-admin/v1/brands/" + brandId, Map.of("name", "무신사 스탠다드", "description", "패션 플랫폼"));

        assertThat(page(USER, firstPriceAscPage()).items())
            .extracting(ProductV1Dto.ProductResponse::brandName).containsExactly("무신사 스탠다드");
    }

    @DisplayName("CACHE-005 · 브랜드를 일괄 삭제하면 키가 지워지고 그 상품이 고객 결과에서 빠지며, 다른 브랜드 상품과 과거 주문은 그대로다")
    @Test
    void brandDeletionEvictsListingsAndHidesProducts() {
        Long deletedBrandProduct = product("셔츠", 10_000);
        Long otherBrand = brandFacade.register("29CM", "셀렉트샵").getId();
        Long otherBrandProduct = productFacade.register(otherBrand, "코트", Price.of(20_000)).getId();
        Long pastOrder = confirmedOrder(deletedBrandProduct, 10_000);
        page(USER, firstPriceAscPage());
        page(USER, "?sort=price_asc");

        admin(HttpMethod.DELETE, "/api-admin/v1/brands/" + brandId, null);
        Set<String> keysAfterDeletion = pageKeys();
        JsonNode order = order(pastOrder);

        assertAll(
            () -> assertThat(keysAfterDeletion).isEmpty(),
            () -> assertThat(page(USER, "?sort=price_asc").items())
                .extracting(ProductV1Dto.ProductResponse::id).as("다른 브랜드 상품만 남는다").containsExactly(otherBrandProduct),
            () -> assertThat(order.path("status").asText()).isEqualTo("CONFIRMED"),
            () -> assertThat(order.path("items").get(0).path("productName").asText()).as("과거 주문은 그대로 조회된다").isEqualTo("셔츠"),
            () -> assertThat(order.path("totalAmount").asLong()).isEqualTo(10_000L)
        );
    }

    @DisplayName("CACHE-005 · 재고 조정으로 품절 여부가 바뀌면 다음 조회에 반영된다. 품절 여부가 그대로인 조정은 키를 지우지 않는다")
    @Test
    void stockAdjustmentRefreshesOnlyWhenSoldOutFlips() {
        Long productId = product("셔츠", 10_000);
        boolean soldOutBefore = page(USER, firstPriceAscPage()).items().get(0).soldOut();

        admin(HttpMethod.PUT, "/api-admin/v1/products/" + productId + "/stock", Map.of("quantity", 5));
        boolean soldOutAfterRestock = page(USER, firstPriceAscPage()).items().get(0).soldOut();

        admin(HttpMethod.PUT, "/api-admin/v1/products/" + productId + "/stock", Map.of("quantity", 3));

        assertAll(
            () -> assertThat(soldOutBefore).isTrue(),
            () -> assertThat(soldOutAfterRestock).isFalse(),
            () -> assertThat(pageKeys()).as("5 → 3 은 품절 여부가 그대로다").hasSize(1)
        );
    }

    @DisplayName("CACHE-005, RSV-001 · 결제 시작이 마지막 재고를 묶으면 다음 조회에서 품절이다")
    @Test
    void confirmationSellingLastStockMarksSoldOut() {
        Long productId = product("셔츠", 10_000);
        productFacade.adjustStock(productId, Quantity.of(1));
        boolean soldOutBefore = page(USER, firstPriceAscPage()).items().get(0).soldOut();
        customer(HttpMethod.POST, "/api/v1/points/charge", USER, Map.of("amount", 10_000));
        Long orderId = customer(HttpMethod.POST, "/api/v1/orders", USER,
            Map.of("lines", List.of(Map.of("productId", productId, "quantity", 1)))).path("data").path("id").asLong();

        customer(HttpMethod.POST, "/api/v1/orders/" + orderId + "/confirm", USER, card(orderId));

        assertAll(
            () -> assertThat(soldOutBefore).isFalse(),
            () -> assertThat(page(USER, firstPriceAscPage()).items().get(0).soldOut()).isTrue()
        );
    }

    @DisplayName("CACHE-007 · 변경이 rollback 되면 키가 지워지지 않고, 캐시는 변경 전 결과를 그대로 준다 — API 로는 rollback 을 고를 수 없어 바깥 트랜잭션에서 관리자 수정을 되돌린다")
    @Test
    void rolledBackChangeKeepsCache() {
        Long productId = product("셔츠", 10_000);
        page(USER, firstPriceAscPage());

        transactionTemplate.executeWithoutResult(status -> {
            productFacade.update(productId, "셔츠", Price.of(1_000));
            status.setRollbackOnly();
        });

        assertAll(
            () -> assertThat(pageKeys()).hasSize(1),
            () -> assertThat(page(USER, firstPriceAscPage()).items())
                .extracting(ProductV1Dto.ProductResponse::price).containsExactly(10_000L)
        );
    }
}
