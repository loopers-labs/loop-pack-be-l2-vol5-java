package com.loopers.interfaces.api;

import com.loopers.fixture.PgCommands;
import com.loopers.fixture.PgTestSupport;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.fixture.ProductDetailCacheCleanUp;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.interfaces.api.order.OrderV1Dto;
import com.loopers.interfaces.api.point.PointV1Dto;
import com.loopers.interfaces.api.product.ProductV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 실습 문서의 연결 흐름: 포인트 충전 API → 여러 품목 주문 확정 → 내 주문·잔액 조회.
 * 모든 단계를 실제 HTTP 요청으로만 수행한다.
 */
@DisplayName("고객은 포인트를 충전해도 PG 결제에서 잔액을 사용하지 않는다.")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomerPurchaseFlowE2ETest extends PgTestSupport {

    private static final ParameterizedTypeReference<ApiResponse<PointV1Dto.PointResponse>> POINT_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrderResponse>> ORDER_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<PageResponse<OrderV1Dto.OrderResponse>>> PAGE_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductResponse>> PRODUCT_TYPE =
        new ParameterizedTypeReference<>() {};

    @Autowired
    private TestRestTemplate testRestTemplate;
    @Autowired
    private UserFixture userFixture;
    @Autowired
    private ProductFixture productFixture;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private ProductDetailCacheCleanUp productDetailCacheCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        productDetailCacheCleanUp.deleteAll();
    }

    private HttpEntity<Object> request(Object body, Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(userId));
        return new HttpEntity<>(body, headers);
    }

    @DisplayName("포인트 10,000을 유지하며 PG로 7,000을 결제한다.")
    @Test
    void chargesConfirmsAndReads() {
        UserModel user = userFixture.createUserWithPoint();
        ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
        ProductModel pants = productFixture.createProduct("바지", 3_000L, 4L);

        // 1. 포인트 충전 API
        ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> charged = testRestTemplate.exchange(
            "/api/v1/points/charge", HttpMethod.POST,
            request(Map.of("amount", 10_000L), user.getId()), POINT_TYPE);

        assertAll(
            () -> assertThat(charged.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(charged.getBody().data().balance()).isEqualTo(10_000L)
        );

        // 2. 여러 품목으로 주문 생성
        Object createBody = Map.of("items", List.of(
            Map.of("productId", shirt.getId(), "quantity", 2),
            Map.of("productId", pants.getId(), "quantity", 1)
        ));
        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> created = testRestTemplate.exchange(
            "/api/v1/orders", HttpMethod.POST, request(createBody, user.getId()), ORDER_TYPE);

        Long orderId = created.getBody().data().id();
        assertAll(
            () -> assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED),
            () -> assertThat(created.getBody().data().status()).isEqualTo("DRAFT"),
            () -> assertThat(created.getBody().data().totalAmount()).isEqualTo(7_000L)
        );

        success(orderId);
        // 3. 주문 확정
        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> confirmed = testRestTemplate.exchange(
            "/api/v1/orders/" + orderId + "/confirm", HttpMethod.POST,
            request(PgCommands.forOrder(orderId), user.getId()), ORDER_TYPE);

        assertAll(
            () -> assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(confirmed.getBody().data().status()).isEqualTo("CONFIRMED"),
            () -> assertThat(confirmed.getBody().data().pointAmount()).isZero(),
            () -> assertThat(confirmed.getBody().data().paidAmount()).isEqualTo(7_000L)
        );

        // 4. 내 주문 목록 조회
        ResponseEntity<ApiResponse<PageResponse<OrderV1Dto.OrderResponse>>> orders = testRestTemplate.exchange(
            "/api/v1/orders", HttpMethod.GET, request(null, user.getId()), PAGE_TYPE);

        PageResponse<OrderV1Dto.OrderResponse> page = orders.getBody().data();
        assertAll(
            () -> assertThat(orders.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(page.totalElements()).isEqualTo(1L),
            () -> assertThat(page.items().get(0).id()).isEqualTo(orderId),
            () -> assertThat(page.items().get(0).status()).isEqualTo("CONFIRMED"),
            () -> assertThat(page.items().get(0).items()).hasSize(2)
        );

        // 5. 내 주문 상세 조회
        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> detail = testRestTemplate.exchange(
            "/api/v1/orders/" + orderId, HttpMethod.GET, request(null, user.getId()), ORDER_TYPE);

        assertAll(
            () -> assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(detail.getBody().data().paidAmount()).isEqualTo(7_000L),
            () -> assertThat(detail.getBody().data().items()).extracting(OrderV1Dto.OrderItemResponse::amount)
                .containsExactlyInAnyOrder(4_000L, 3_000L)
        );

        // 6. 잔액 조회 — PG 결제 후에도 충전한 10,000을 유지한다
        ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> balance = testRestTemplate.exchange(
            "/api/v1/points", HttpMethod.GET, request(null, user.getId()), POINT_TYPE);

        assertAll(
            () -> assertThat(balance.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(balance.getBody().data().balance()).isEqualTo(10_000L)
        );

        // 7. 상품 상세 조회 — 확정으로 차감된 재고가 보인다
        ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> shirtDetail = testRestTemplate.exchange(
            "/api/v1/products/" + shirt.getId(), HttpMethod.GET, request(null, user.getId()), PRODUCT_TYPE);
        ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> pantsDetail = testRestTemplate.exchange(
            "/api/v1/products/" + pants.getId(), HttpMethod.GET, request(null, user.getId()), PRODUCT_TYPE);

        assertAll(
            () -> assertThat(shirtDetail.getBody().data().stockQuantity()).isEqualTo(3L),
            () -> assertThat(pantsDetail.getBody().data().stockQuantity()).isEqualTo(3L)
        );
    }
}
