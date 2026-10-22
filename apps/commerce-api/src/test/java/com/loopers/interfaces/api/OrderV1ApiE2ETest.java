package com.loopers.interfaces.api;

import com.loopers.application.payment.PgPaymentPort;
import com.loopers.application.payment.PgPaymentResult;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.interfaces.api.order.OrderV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderV1ApiE2ETest {

    private static final String USER_ID_HEADER = "X-USER-ID";
    private static final ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrderResponse>> ORDER_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrdersResponse>> ORDERS_TYPE =
        new ParameterizedTypeReference<>() {};

    private final TestRestTemplate testRestTemplate;
    private final UserJpaRepository userJpaRepository;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    private UserModel user;
    private ProductModel product;

    @MockBean
    PgPaymentPort pgPaymentPort;

    @Autowired
    PointJpaRepository pointJpaRepository;

    @Autowired
    public OrderV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        UserJpaRepository userJpaRepository,
        BrandJpaRepository brandJpaRepository,
        ProductJpaRepository productJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.userJpaRepository = userJpaRepository;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        user = userJpaRepository.save(new UserModel("실습 사용자"));
        BrandModel brand = brandJpaRepository.save(new BrandModel("브랜드A"));
        product = productJpaRepository.save(new ProductModel(brand.getId(), "상품", 3_500L, 5));

        when(pgPaymentPort.pay(any())).thenReturn(
            new PgPaymentResult("TX-SUCCESS", "LP-ORD-0000000001", 7_000L, PgPaymentResult.PgStatus.SUCCESS)
        );
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

    private ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> createOrder(
        Long userId, List<OrderV1Dto.ItemRequest> items
    ) {
        return testRestTemplate.exchange(
            "/api/v1/orders", HttpMethod.POST,
            new HttpEntity<>(new OrderV1Dto.CreateRequest(items), headersOf(userId)), ORDER_TYPE
        );
    }

    private ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> confirmOrder(Long userId, Long orderId) {
        var request = new OrderV1Dto.ConfirmRequest(UUID.randomUUID().toString(), "http://localhost/cb");
        return testRestTemplate.exchange(
            "/api/v1/orders/" + orderId + "/confirm", HttpMethod.POST,
            new HttpEntity<>(request, headersOf(userId)), ORDER_TYPE
        );
    }

    private int storedStock() {
        return productJpaRepository.findById(product.getId()).get().getStock();
    }

    @DisplayName("주문 생성은 중복 상품 품목을 합산해 DRAFT로 저장하고, 재고를 차감하지 않는다.")
    @Test
    void createsDraftOrder_mergingDuplicateItems_withoutDeduction() {
        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = createOrder(user.getId(), List.of(
            new OrderV1Dto.ItemRequest(product.getId(), 2),
            new OrderV1Dto.ItemRequest(product.getId(), 3)
        ));

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
        ProductModel deleted = new ProductModel(product.getBrandId(), "삭제된 상품", 1_000L, 5);
        deleted.delete();
        ProductModel savedDeleted = productJpaRepository.save(deleted);

        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> zeroQuantity = createOrder(user.getId(), List.of(
            new OrderV1Dto.ItemRequest(product.getId(), 0)
        ));
        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> deletedProduct = createOrder(user.getId(), List.of(
            new OrderV1Dto.ItemRequest(savedDeleted.getId(), 1)
        ));

        assertAll(
            () -> assertThat(zeroQuantity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
            () -> assertThat(deletedProduct.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
        );
    }

    @DisplayName("PG 성공 시 주문이 CONFIRMED되고 재고가 차감된다.")
    @Test
    void confirmsOrder_deductingStock_whenPgSucceeds() {
        Long orderId = createOrder(user.getId(), List.of(
            new OrderV1Dto.ItemRequest(product.getId(), 2)
        )).getBody().data().id();

        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(user.getId(), orderId);

        assertAll(
            () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
            () -> assertThat(response.getBody().data().status()).isEqualTo("CONFIRMED"),
            () -> assertThat(response.getBody().data().paidAmount()).isEqualTo(7_000L),
            () -> assertThat(storedStock()).isEqualTo(3)
        );
    }

    @DisplayName("재고가 부족하면 확정을 400으로 거절하고, 재고를 유지한다.")
    @Test
    void rejectsConfirm_whenStockIsInsufficient() {
        Long orderId = createOrder(user.getId(), List.of(
            new OrderV1Dto.ItemRequest(product.getId(), 3),
            new OrderV1Dto.ItemRequest(product.getId(), 3)
        )).getBody().data().id();

        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(user.getId(), orderId);

        assertAll(
            () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
            () -> assertThat(storedStock()).isEqualTo(5)
        );
    }

    @DisplayName("타인의 주문 확정 요청은 404, 이미 확정된 주문의 재확정은 409다.")
    @Test
    void rejectsConfirm_forOthersOrder_andAlreadyConfirmedOrder() {
        UserModel other = userJpaRepository.save(new UserModel("다른 사용자"));
        Long orderId = createOrder(user.getId(), List.of(
            new OrderV1Dto.ItemRequest(product.getId(), 2)
        )).getBody().data().id();

        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> othersConfirm = confirmOrder(other.getId(), orderId);
        confirmOrder(user.getId(), orderId);
        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> reConfirm = confirmOrder(user.getId(), orderId);

        assertAll(
            () -> assertThat(othersConfirm.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
            () -> assertThat(reConfirm.getStatusCode()).isEqualTo(HttpStatus.CONFLICT)
        );
    }

    @DisplayName("DRAFT 생성 후 상품 가격이 바뀌어도, 확정 결제액은 생성 시점 단가를 따른다.")
    @Test
    void confirmsWithDraftUnitPrice_whenProductPriceChangedAfterDraft() {
        Long orderId = createOrder(user.getId(), List.of(
            new OrderV1Dto.ItemRequest(product.getId(), 2)
        )).getBody().data().id();
        product.update("상품", 5_000L);
        productJpaRepository.save(product);

        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(user.getId(), orderId);

        assertThat(response.getBody().data().paidAmount()).isEqualTo(7_000L);
    }

    @DisplayName("내 주문 목록·상세는 내 주문만 반환하고, 타인 주문 상세는 404다.")
    @Test
    void returnsMyOrders_andHidesOthersOrders() {
        UserModel other = userJpaRepository.save(new UserModel("다른 사용자"));
        Long myOrderId = createOrder(user.getId(), List.of(
            new OrderV1Dto.ItemRequest(product.getId(), 1)
        )).getBody().data().id();
        createOrder(other.getId(), List.of(
            new OrderV1Dto.ItemRequest(product.getId(), 1)
        ));

        ResponseEntity<ApiResponse<OrderV1Dto.OrdersResponse>> list = testRestTemplate.exchange(
            "/api/v1/orders", HttpMethod.GET,
            new HttpEntity<>(headersOf(user.getId())), ORDERS_TYPE
        );
        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> myDetail = testRestTemplate.exchange(
            "/api/v1/orders/" + myOrderId, HttpMethod.GET,
            new HttpEntity<>(headersOf(user.getId())), ORDER_TYPE
        );
        ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> othersDetail = testRestTemplate.exchange(
            "/api/v1/orders/" + myOrderId, HttpMethod.GET,
            new HttpEntity<>(headersOf(other.getId())), ORDER_TYPE
        );

        assertAll(
            () -> assertThat(list.getBody().data().items()).hasSize(1),
            () -> assertThat(myDetail.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(othersDetail.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND)
        );
    }
}
