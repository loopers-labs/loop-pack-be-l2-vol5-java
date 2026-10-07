package com.loopers.interfaces.api;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.point.PointModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.interfaces.api.order.OrderV1Dto;
import com.loopers.interfaces.api.point.PointV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderV1ApiE2ETest {

    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrderResponse>> ORDER_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrdersResponse>> ORDERS_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<PointV1Dto.PointResponse>> POINT_TYPE =
        new ParameterizedTypeReference<>() {};

    private final TestRestTemplate testRestTemplate;
    private final UserJpaRepository userJpaRepository;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final PointJpaRepository pointJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    private UserModel user;
    private ProductModel product;

    @Autowired
    public OrderV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        UserJpaRepository userJpaRepository,
        BrandJpaRepository brandJpaRepository,
        ProductJpaRepository productJpaRepository,
        PointJpaRepository pointJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.userJpaRepository = userJpaRepository;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.pointJpaRepository = pointJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        user = userJpaRepository.save(new UserModel("실습 사용자"));
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        product = productJpaRepository.save(new ProductModel(brand.getId(), "상품", 3_500L, 5));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private HttpHeaders headersOf(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set(USER_ID_HEADER, String.valueOf(userId));
        }
        return headers;
    }

    private void charge(Long userId, long amount) {
        testRestTemplate.exchange(
            "/api/v1/points/charge", HttpMethod.POST,
            new HttpEntity<>(new PointV1Dto.ChargeRequest(amount), headersOf(userId)), POINT_TYPE
        );
    }

    private ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> createOrder(
        Long userId, List<OrderV1Dto.ItemRequest> items
    ) {
        return testRestTemplate.exchange(
            "/api/v1/orders", HttpMethod.POST,
            new HttpEntity<>(new OrderV1Dto.CreateRequest(items), headersOf(userId)), ORDER_TYPE
        );
    }

    private ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> confirmOrder(Long userId, Long orderId) {
        return testRestTemplate.exchange(
            "/api/v1/orders/" + orderId + "/confirm", HttpMethod.POST,
            new HttpEntity<>(null, headersOf(userId)), ORDER_TYPE
        );
    }

    private int storedStock() {
        return productJpaRepository.findById(product.getId()).get().getStock();
    }

    private long storedBalance() {
        return pointJpaRepository.findByUserId(user.getId()).map(PointModel::getBalance).orElse(0L);
    }

    @DisplayName("주문 생성은 중복 상품 품목을 합산해 DRAFT로 저장하고, 재고·포인트를 차감하지 않는다.")
    @Test
    void createsDraftOrder_mergingDuplicateItems_withoutDeduction() {
        // act — 같은 상품 2개 + 3개
        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = createOrder(user.getId(), List.of(
            new OrderV1Dto.ItemRequest(product.getId(), 2),
            new OrderV1Dto.ItemRequest(product.getId(), 3)
        ));

        // assert
        assertAll(
            () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
            () -> assertThat(response.getBody().data().status()).isEqualTo("DRAFT"),
            () -> assertThat(response.getBody().data().items()).hasSize(1),
            () -> assertThat(response.getBody().data().items().get(0).quantity()).isEqualTo(5),
            () -> assertThat(response.getBody().data().totalAmount()).isEqualTo(17_500L),
            () -> assertThat(response.getBody().data().paidAmount()).isNull(),
            () -> assertThat(storedStock()).isEqualTo(5)
        );
    }

    @DisplayName("수량이 0 이하이거나 삭제된 상품이 포함된 주문 생성은, 400으로 거절한다.")
    @Test
    void rejectsOrderCreation_whenItemIsInvalid() {
        // arrange
        ProductModel deleted = new ProductModel(product.getBrandId(), "삭제된 상품", 1_000L, 5);
        deleted.delete();
        ProductModel savedDeleted = productJpaRepository.save(deleted);

        // act
        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> zeroQuantity = createOrder(user.getId(), List.of(
            new OrderV1Dto.ItemRequest(product.getId(), 0)
        ));
        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> deletedProduct = createOrder(user.getId(), List.of(
            new OrderV1Dto.ItemRequest(savedDeleted.getId(), 1)
        ));

        // assert
        assertAll(
            () -> assertThat(zeroQuantity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
            () -> assertThat(deletedProduct.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
        );
    }

    @DisplayName("연결 흐름 — 잔액 0에서 10,000 충전 후 7,000 주문을 확정하면, 재고·포인트가 차감되고 잔액은 3,000이다.")
    @Test
    void confirmsOrder_deductingStockAndPoint() {
        // arrange
        charge(user.getId(), 10_000L);
        Long orderId = createOrder(user.getId(), List.of(
            new OrderV1Dto.ItemRequest(product.getId(), 2)
        )).getBody().data().id();

        // act — 3,500원 × 2개 = 7,000원 결제
        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(user.getId(), orderId);

        // assert
        assertAll(
            () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
            () -> assertThat(response.getBody().data().status()).isEqualTo("CONFIRMED"),
            () -> assertThat(response.getBody().data().paidAmount()).isEqualTo(7_000L),
            () -> assertThat(storedStock()).isEqualTo(3),
            () -> assertThat(storedBalance()).isEqualTo(3_000L)
        );
    }

    @DisplayName("잔액이 부족하면 확정을 400으로 거절하고, 이미 차감된 재고까지 원상 복구한다.")
    @Test
    void rejectsConfirm_andRollsBackStock_whenBalanceIsInsufficient() {
        // arrange — 잔액 1,000, 주문 7,000
        charge(user.getId(), 1_000L);
        Long orderId = createOrder(user.getId(), List.of(
            new OrderV1Dto.ItemRequest(product.getId(), 2)
        )).getBody().data().id();

        // act
        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(user.getId(), orderId);

        // assert — 트랜잭션 롤백으로 재고·잔액·상태 모두 유지
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
            () -> assertThat(storedStock()).isEqualTo(5),
            () -> assertThat(storedBalance()).isEqualTo(1_000L),
            () -> assertThat(confirmOrder(user.getId(), orderId).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST)
        );
    }

    @DisplayName("재고가 부족하면 확정을 400으로 거절하고, 잔액을 유지한다.")
    @Test
    void rejectsConfirm_whenStockIsInsufficient() {
        // arrange — 재고 5, 주문 6
        charge(user.getId(), 30_000L);
        Long orderId = createOrder(user.getId(), List.of(
            new OrderV1Dto.ItemRequest(product.getId(), 3),
            new OrderV1Dto.ItemRequest(product.getId(), 3)
        )).getBody().data().id();

        // act
        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(user.getId(), orderId);

        // assert
        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
            () -> assertThat(storedStock()).isEqualTo(5),
            () -> assertThat(storedBalance()).isEqualTo(30_000L)
        );
    }

    @DisplayName("타인의 주문 확정 요청은 404, 이미 확정된 주문의 재확정은 409다.")
    @Test
    void rejectsConfirm_forOthersOrder_andAlreadyConfirmedOrder() {
        // arrange
        UserModel other = userJpaRepository.save(new UserModel("다른 사용자"));
        charge(user.getId(), 10_000L);
        Long orderId = createOrder(user.getId(), List.of(
            new OrderV1Dto.ItemRequest(product.getId(), 2)
        )).getBody().data().id();

        // act
        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> othersConfirm = confirmOrder(other.getId(), orderId);
        confirmOrder(user.getId(), orderId);
        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> reConfirm = confirmOrder(user.getId(), orderId);

        // assert
        assertAll(
            () -> assertThat(othersConfirm.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
            () -> assertThat(reConfirm.getStatusCode()).isEqualTo(HttpStatus.CONFLICT)
        );
    }

    @DisplayName("DRAFT 생성 후 상품 가격이 바뀌어도, 확정 결제액은 생성 시점 단가를 따른다.")
    @Test
    void confirmsWithDraftUnitPrice_whenProductPriceChangedAfterDraft() {
        // arrange — 단가 3,500으로 DRAFT 생성 후 가격을 5,000으로 인상
        charge(user.getId(), 10_000L);
        Long orderId = createOrder(user.getId(), List.of(
            new OrderV1Dto.ItemRequest(product.getId(), 2)
        )).getBody().data().id();
        product.update("상품", 5_000L);
        productJpaRepository.save(product);

        // act
        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(user.getId(), orderId);

        // assert — 5,000이 아닌 3,500 기준 7,000 결제
        assertAll(
            () -> assertThat(response.getBody().data().paidAmount()).isEqualTo(7_000L),
            () -> assertThat(storedBalance()).isEqualTo(3_000L)
        );
    }

    @DisplayName("내 주문 목록·상세는 내 주문만 반환하고, 타인 주문 상세는 404다.")
    @Test
    void returnsMyOrders_andHidesOthersOrders() {
        // arrange
        UserModel other = userJpaRepository.save(new UserModel("다른 사용자"));
        Long myOrderId = createOrder(user.getId(), List.of(
            new OrderV1Dto.ItemRequest(product.getId(), 1)
        )).getBody().data().id();
        Long othersOrderId = createOrder(other.getId(), List.of(
            new OrderV1Dto.ItemRequest(product.getId(), 1)
        )).getBody().data().id();

        // act
        ResponseEntity<ApiResponse<OrderV1Dto.OrdersResponse>> myOrders = testRestTemplate.exchange(
            "/api/v1/orders", HttpMethod.GET, new HttpEntity<>(null, headersOf(user.getId())), ORDERS_TYPE
        );
        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> othersDetail = testRestTemplate.exchange(
            "/api/v1/orders/" + othersOrderId, HttpMethod.GET,
            new HttpEntity<>(null, headersOf(user.getId())), ORDER_TYPE
        );

        // assert
        assertAll(
            () -> assertThat(myOrders.getBody().data().items()).hasSize(1),
            () -> assertThat(myOrders.getBody().data().items().get(0).id()).isEqualTo(myOrderId),
            () -> assertThat(othersDetail.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND)
        );
    }
}
