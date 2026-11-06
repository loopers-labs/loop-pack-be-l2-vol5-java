package com.loopers.interfaces.api;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.interfaces.api.order.OrderV1Dto;
import com.loopers.interfaces.api.point.PointV1Dto;
import com.loopers.support.pg.PaymentTestSupport;
import com.loopers.support.pg.TestPgResetExtension;
import com.loopers.support.pg.TestPgServer;
import com.loopers.support.pg.TestPgStatus;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(TestPgResetExtension.class)
class OrderV1ApiE2ETest {

    private final TestPgServer pg = TestPgServer.shared();
    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public OrderV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        BrandJpaRepository brandJpaRepository,
        ProductJpaRepository productJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private HttpEntity<Object> withUser(Long userId, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(userId));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private ProductModel saveProduct(Long price, int stock) {
        BrandModel brand = brandJpaRepository.save(new BrandModel("나이키"));
        return productJpaRepository.save(new ProductModel(brand.getId(), "runner", price, stock));
    }

    private void charge(Long userId, Long amount) {
        testRestTemplate.exchange("/api/v1/points/charge", HttpMethod.POST,
            withUser(userId, new PointV1Dto.ChargeRequest(amount)), Void.class);
    }

    private ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> createOrder(Long userId, List<OrderV1Dto.OrderItemRequest> items) {
        return testRestTemplate.exchange("/api/v1/orders", HttpMethod.POST,
            withUser(userId, new OrderV1Dto.CreateRequest(items)), new ParameterizedTypeReference<>() {});
    }

    // 주문마다 결제 시도 키가 달라야 하므로 키는 주문 id로 만든다. 카드 값은 합성 값만 쓴다
    private ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> confirmOrder(Long userId, Long orderId) {
        return testRestTemplate.exchange("/api/v1/orders/" + orderId + "/confirm", HttpMethod.POST,
            withUser(userId, new OrderV1Dto.ConfirmRequest(
                PaymentTestSupport.attemptKeyOf(orderId), PaymentTestSupport.CARD_TYPE, PaymentTestSupport.CARD_NO)),
            new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> reconcileOrder(Long userId, Long orderId) {
        return testRestTemplate.exchange("/api/v1/orders/" + orderId + "/payment/reconcile", HttpMethod.POST,
            withUser(userId, null), new ParameterizedTypeReference<>() {});
    }

    private Long getBalance(Long userId) {
        ResponseEntity<ApiResponse<PointV1Dto.PointResponse>> response = testRestTemplate.exchange(
            "/api/v1/points", HttpMethod.GET, withUser(userId, null), new ParameterizedTypeReference<>() {});
        return response.getBody().data().balance();
    }

    @DisplayName("대표 흐름: 주문 생성 → PG 결제 시작 → 결과 확인")
    @Nested
    class RepresentativeFlow {
        @DisplayName("주문을 확정하면 202와 함께 재고만 확보되고, PG 성공을 확인하면 200으로 확정된다. 포인트는 어느 단계에서도 쓰지 않는다.")
        @Test
        void completesOrder_endToEnd() {
            // arrange
            ProductModel product = saveProduct(7_000L, 5);
            charge(1L, 10_000L);

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> createResponse =
                createOrder(1L, List.of(new OrderV1Dto.OrderItemRequest(product.getId(), 1)));
            Long orderId = createResponse.getBody().data().id();

            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> confirmResponse = confirmOrder(1L, orderId);
            Long balanceWhilePending = getBalance(1L);
            int stockWhilePending = productJpaRepository.findById(product.getId()).orElseThrow().getStock();

            PaymentTestSupport.completeAt(pg, orderId, TestPgStatus.SUCCESS);
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> reconcileResponse = reconcileOrder(1L, orderId);

            // assert
            assertThat(createResponse.getBody().data().status().name()).isEqualTo("DRAFT");
            assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(confirmResponse.getBody().data().status().name()).isEqualTo("PAYMENT_PENDING");
            assertThat(confirmResponse.getBody().data().paymentMethod().name()).isEqualTo("PG");
            assertThat(confirmResponse.getBody().data().reconcilePath()).isEqualTo("/api/v1/orders/" + orderId + "/payment/reconcile");
            assertThat(confirmResponse.getBody().data().orderPath()).isEqualTo("/api/v1/orders/" + orderId);
            assertThat(balanceWhilePending).isEqualTo(10_000L);
            assertThat(stockWhilePending).isEqualTo(4);

            assertThat(reconcileResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(reconcileResponse.getBody().data().status().name()).isEqualTo("CONFIRMED");
            assertThat(reconcileResponse.getBody().data().paidAmount()).isEqualTo(7_000L);
            assertThat(reconcileResponse.getBody().data().reconcilePath()).isNull();
            assertThat(reconcileResponse.getBody().data().orderPath()).isNull();
            assertThat(getBalance(1L)).isEqualTo(10_000L);

            ProductModel afterConfirm = productJpaRepository.findById(product.getId()).orElseThrow();
            assertThat(afterConfirm.getStock()).isEqualTo(4);
            assertThat(pg.createCount()).isEqualTo(1);
        }
    }

    @DisplayName("POST /api/v1/orders")
    @Nested
    class Create {
        @DisplayName("존재하지 않는 상품을 담으면, 404를 응답한다.")
        @Test
        void returns404_whenProductDoesNotExist() {
            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response =
                createOrder(1L, List.of(new OrderV1Dto.OrderItemRequest(999L, 1)));

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("수량이 0 이하이면, 400을 응답한다.")
        @Test
        void returns400_whenQuantityIsNotPositive() {
            // arrange
            ProductModel product = saveProduct(10_000L, 5);

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response =
                createOrder(1L, List.of(new OrderV1Dto.OrderItemRequest(product.getId(), 0)));

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("같은 상품이 여러 줄로 담기면, 수량을 합산한 하나의 품목으로 저장된다.")
        @Test
        void mergesDuplicateProductLines() {
            // arrange
            ProductModel product = saveProduct(10_000L, 5);

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = createOrder(1L, List.of(
                new OrderV1Dto.OrderItemRequest(product.getId(), 2),
                new OrderV1Dto.OrderItemRequest(product.getId(), 1)
            ));

            // assert
            assertThat(response.getBody().data().items()).hasSize(1);
            assertThat(response.getBody().data().items().get(0).quantity()).isEqualTo(3);
            assertThat(response.getBody().data().totalAmount()).isEqualTo(30_000L);
        }
    }

    @DisplayName("POST /api/v1/orders/{orderId}/confirm")
    @Nested
    class Confirm {
        @DisplayName("본인 소유가 아닌 주문을 확정하려 하면, 404를 응답한다.")
        @Test
        void returns404_whenNotOwner() {
            // arrange
            ProductModel product = saveProduct(10_000L, 5);
            charge(1L, 10_000L);
            Long orderId = createOrder(1L, List.of(new OrderV1Dto.OrderItemRequest(product.getId(), 1)))
                .getBody().data().id();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(2L, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(pg.createCount()).isZero();
        }

        @DisplayName("재고가 부족하면, 400을 응답하고 재고와 잔액이 그대로 유지되며 PG는 호출되지 않는다.")
        @Test
        void returns400_andRollsBack_whenStockIsInsufficient() {
            // arrange
            ProductModel product = saveProduct(10_000L, 1);
            charge(1L, 100_000L);
            Long orderId = createOrder(1L, List.of(new OrderV1Dto.OrderItemRequest(product.getId(), 2)))
                .getBody().data().id();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(1L, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(getBalance(1L)).isEqualTo(100_000L);
            ProductModel unchanged = productJpaRepository.findById(product.getId()).orElseThrow();
            assertThat(unchanged.getStock()).isEqualTo(1);
            assertThat(pg.createCount()).isZero();
        }

        @DisplayName("포인트 잔액이 주문 금액보다 적어도, PG 결제는 잔액을 보지 않아 시작되고 잔액은 그대로다.")
        @Test
        void startsPgPayment_regardlessOfPointBalance() {
            // arrange
            ProductModel product = saveProduct(10_000L, 5);
            charge(1L, 1_000L);
            Long orderId = createOrder(1L, List.of(new OrderV1Dto.OrderItemRequest(product.getId(), 1)))
                .getBody().data().id();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(1L, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(getBalance(1L)).isEqualTo(1_000L);
            ProductModel reserved = productJpaRepository.findById(product.getId()).orElseThrow();
            assertThat(reserved.getStock()).isEqualTo(4);
        }

        @DisplayName("결제 시도 키·카드 종류·카드 번호가 비어 있거나 요청 본문이 없으면, 400을 응답하고 아무것도 바뀌지 않는다.")
        @Test
        void returns400_whenPaymentInputIsMissing() {
            // arrange
            ProductModel product = saveProduct(10_000L, 5);
            Long orderId = createOrder(1L, List.of(new OrderV1Dto.OrderItemRequest(product.getId(), 1)))
                .getBody().data().id();
            List<Object> invalidBodies = new java.util.ArrayList<>(List.of(
                new OrderV1Dto.ConfirmRequest(" ", PaymentTestSupport.CARD_TYPE, PaymentTestSupport.CARD_NO),
                new OrderV1Dto.ConfirmRequest("key-1", null, PaymentTestSupport.CARD_NO),
                new OrderV1Dto.ConfirmRequest("key-1", PaymentTestSupport.CARD_TYPE, "")
            ));
            invalidBodies.add(null);

            // act & assert
            for (Object body : invalidBodies) {
                ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = testRestTemplate.exchange(
                    "/api/v1/orders/" + orderId + "/confirm", HttpMethod.POST, withUser(1L, body), new ParameterizedTypeReference<>() {});
                assertThat(response.getStatusCode()).as(String.valueOf(body)).isEqualTo(HttpStatus.BAD_REQUEST);
            }
            assertThat(productJpaRepository.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
            assertThat(pg.createCount()).isZero();
        }

        @DisplayName("다른 사용자의 주문은 결제 상태를 다시 확인(reconcile)할 수 없다. 404를 응답한다.")
        @Test
        void returns404_whenReconcilingOthersOrder() {
            // arrange
            ProductModel product = saveProduct(10_000L, 5);
            Long orderId = createOrder(1L, List.of(new OrderV1Dto.OrderItemRequest(product.getId(), 1)))
                .getBody().data().id();
            confirmOrder(1L, orderId);

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = reconcileOrder(2L, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("GET /api/v1/orders, GET /api/v1/orders/{orderId}")
    @Nested
    class Get {
        @DisplayName("본인의 주문 목록을 조회하면, 다른 사용자의 주문은 포함되지 않는다.")
        @Test
        void excludesOtherUsersOrders() {
            // arrange
            ProductModel product = saveProduct(10_000L, 5);
            createOrder(1L, List.of(new OrderV1Dto.OrderItemRequest(product.getId(), 1)));
            createOrder(2L, List.of(new OrderV1Dto.OrderItemRequest(product.getId(), 1)));

            // act
            ResponseEntity<ApiResponse<List<OrderV1Dto.OrderResponse>>> response = testRestTemplate.exchange(
                "/api/v1/orders", HttpMethod.GET, withUser(1L, null), new ParameterizedTypeReference<>() {});

            // assert
            assertThat(response.getBody().data()).hasSize(1);
        }

        @DisplayName("결제를 시작한 주문을 조회하면, 결제수단과 결제 시도 상태가 보이고 PG 내부 값(거래 키)은 보이지 않는다.")
        @Test
        void showsPaymentState_withoutPgInternals() {
            // arrange
            ProductModel product = saveProduct(10_000L, 5);
            Long orderId = createOrder(1L, List.of(new OrderV1Dto.OrderItemRequest(product.getId(), 1)))
                .getBody().data().id();
            confirmOrder(1L, orderId);

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = testRestTemplate.exchange(
                "/api/v1/orders/" + orderId, HttpMethod.GET, withUser(1L, null), new ParameterizedTypeReference<>() {});
            ResponseEntity<String> raw = testRestTemplate.exchange(
                "/api/v1/orders/" + orderId, HttpMethod.GET, withUser(1L, null), String.class);

            // assert
            OrderV1Dto.OrderResponse data = response.getBody().data();
            assertThat(data.status().name()).isEqualTo("PAYMENT_PENDING");
            assertThat(data.paymentMethod().name()).isEqualTo("PG");
            assertThat(data.payment().status().name()).isEqualTo("PENDING");
            assertThat(data.reconcilePath()).isNotNull();
            assertThat(raw.getBody()).doesNotContain("transactionKey", "TEST-TX-", PaymentTestSupport.CARD_NO);
        }

        @DisplayName("DRAFT 주문에는 결제수단과 결제 상태가 없다.")
        @Test
        void hidesPaymentState_whenOrderIsDraft() {
            // arrange
            ProductModel product = saveProduct(10_000L, 5);
            Long orderId = createOrder(1L, List.of(new OrderV1Dto.OrderItemRequest(product.getId(), 1)))
                .getBody().data().id();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = testRestTemplate.exchange(
                "/api/v1/orders/" + orderId, HttpMethod.GET, withUser(1L, null), new ParameterizedTypeReference<>() {});

            // assert
            assertThat(response.getBody().data().paymentMethod()).isNull();
            assertThat(response.getBody().data().payment()).isNull();
            assertThat(response.getBody().data().reconcilePath()).isNull();
        }

        @DisplayName("다른 사용자의 주문 상세를 조회하면, 404를 응답한다.")
        @Test
        void returns404_whenNotOwner() {
            // arrange
            ProductModel product = saveProduct(10_000L, 5);
            Long orderId = createOrder(1L, List.of(new OrderV1Dto.OrderItemRequest(product.getId(), 1)))
                .getBody().data().id();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = testRestTemplate.exchange(
                "/api/v1/orders/" + orderId, HttpMethod.GET, withUser(2L, null), new ParameterizedTypeReference<>() {});

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
