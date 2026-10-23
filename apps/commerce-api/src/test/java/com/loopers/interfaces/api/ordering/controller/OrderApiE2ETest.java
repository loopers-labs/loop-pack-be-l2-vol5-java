package com.loopers.interfaces.api.ordering.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import com.loopers.application.common.PageResult;
import com.loopers.application.mall.command.BrandCommand;
import com.loopers.application.mall.usecase.DeleteBrandUseCase;
import com.loopers.application.ordering.query.AdminOrderView;
import com.loopers.application.ordering.query.OrderView;
import com.loopers.application.pay.gateway.PaymentProviderGateway;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.mall.model.Brand;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.repository.BrandRepository;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.domain.ordering.model.OrderRecordStatus;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.pay.model.Wallet;
import com.loopers.domain.pay.repository.WalletRepository;
import com.loopers.domain.shared.Money;
import com.loopers.domain.shopping.model.User;
import com.loopers.domain.shopping.repository.UserRepository;
import com.loopers.infrastructure.persistence.ordering.entity.QOrderJpaEntity;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.ordering.dto.OrderApiDto;
import com.loopers.support.test.E2ETest;
import com.loopers.support.test.PgPaymentTestSupport;
import org.junit.jupiter.api.BeforeEach;
import com.loopers.utils.DatabaseCleanUp;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@E2ETest
class OrderApiE2ETest {
    @Test
    @DisplayName("수동 대사는 본인/관리자 접근을 검사하고 고객 202·관리자 완료 200과 각 조회 경로를 반환한다")
    void reconcilesOnlyOwnedOrAdministratorPayment() {
        // arrange
        userRepository.save(User.create(1L));
        userRepository.save(User.create(2L));
        long productId = createProduct("상품", 1000L, 5);
        long orderId = createOrder(2L, List.of(new OrderApiDto.ItemRequest(productId, 2))).getBody().data().orderId();
        restTemplate.exchange("/api/v1/orders/" + orderId + "/confirm", HttpMethod.POST,
            new HttpEntity<>(confirmBody(orderId), headers("2")), String.class);
        String customer = "/api/v1/orders/" + orderId + "/payment/reconcile";
        String administrator = "/api-admin/v1/orders/" + orderId + "/payment/reconcile";
        String missing = "/api/v1/orders/999999/payment/reconcile";
        var before = tasks.findByKey(confirmBody(orderId).paymentAttemptKey()).orElseThrow();
        org.mockito.Mockito.doAnswer(invocation -> {
            PaymentProviderGateway.ExpectedPayment expected = invocation.getArgument(0);
            return com.loopers.domain.pay.model.PaymentObservation.verified(expected.transactionKey(),
                expected.providerOrderId(), expected.amount(), com.loopers.domain.pay.model.ProviderPaymentStatus.PENDING);
        }).when(gateway).lookup(any());
        // act / assert
        ResponseEntity<String> other = postReconcile(customer, "1");
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(other.getBody()).isEqualTo(postReconcile(missing, "1").getBody());
        assertThat(postReconcile(administrator, "2").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.postForEntity(customer, HttpEntity.EMPTY, String.class).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tasks.findByKey(confirmBody(orderId).paymentAttemptKey()).orElseThrow().snapshot()).isEqualTo(before.snapshot());
        verify(gateway, never()).lookup(any());
        ResponseEntity<ApiResponse<OrderView>> pending = restTemplate.exchange(customer, HttpMethod.POST,
            new HttpEntity<>(null, headers("2")), new ParameterizedTypeReference<>() {});
        assertThat(pending.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(pending.getBody().data().paymentAttempt().recoveryState().name()).isEqualTo("MANUAL_REQUIRED");
        assertThat(pending.getBody().data().paymentLookupUrl()).isEqualTo("/api/v1/orders/" + orderId);
        org.mockito.Mockito.verify(gateway).lookup(any());
        org.mockito.Mockito.doAnswer(invocation -> {
            PaymentProviderGateway.ExpectedPayment expected = invocation.getArgument(0);
            return com.loopers.domain.pay.model.PaymentObservation.verified(expected.transactionKey(),
                expected.providerOrderId(), expected.amount(), com.loopers.domain.pay.model.ProviderPaymentStatus.SUCCESS);
        }).when(gateway).lookup(any());
        ResponseEntity<ApiResponse<AdminOrderView>> completed = restTemplate.exchange(administrator, HttpMethod.POST,
            new HttpEntity<>(null, headers("1")), new ParameterizedTypeReference<>() {});
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completed.getBody().data().userId()).isEqualTo(2L);
        assertThat(completed.getBody().data().status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(completed.getBody().data().paymentLookupUrl()).isEqualTo("/api-admin/v1/orders/" + orderId);
        assertThat(completed.getBody().data().paymentAttempt().usedRounds()).isZero();
        assertThat(currentStock(productId)).isEqualTo(3);
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.times(2)).lookup(any());
        assertThat(postReconcile(customer, "2").getStatusCode()).isEqualTo(HttpStatus.OK);
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.times(2)).lookup(any());
        org.mockito.Mockito.verify(gateway).create(any(), any(), any());
    }

    private ResponseEntity<String> postReconcile(String path, String userId) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(null, headers(userId)), String.class);
    }

    @Autowired private com.loopers.domain.ordering.repository.OrderRepository orders;
    @Autowired private com.loopers.domain.pay.repository.PaymentRecoveryTaskRepository tasks;

    @Test
    @DisplayName("관리자가 타인 미완료 주문의 안내된 조회 경로를 그대로 따라가도 200을 받는다")
    void followsAdministratorLookupUrlForAnotherCustomer() {
        // arrange
        userRepository.save(User.create(1L));
        userRepository.save(User.create(2L));
        long productId = createProduct("상품", 1000L, 5);
        long orderId = createOrder(2L, List.of(new OrderApiDto.ItemRequest(productId, 1))).getBody().data().orderId();
        restTemplate.exchange("/api/v1/orders/" + orderId + "/confirm", HttpMethod.POST,
            new HttpEntity<>(confirmBody(orderId), headers("2")), String.class);
        // act
        ResponseEntity<ApiResponse<PageResult<AdminOrderView>>> list = restTemplate.exchange(
            "/api-admin/v1/payments/incomplete", HttpMethod.GET, new HttpEntity<>(null, headers("1")),
            new ParameterizedTypeReference<>() {});
        AdminOrderView listed = list.getBody().data().items().get(0);
        // assert
        assertThat(getQuery(listed.paymentLookupUrl(), "1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.paymentLookupUrl()).isEqualTo("/api-admin/v1/orders/" + orderId);
        assertThat(getQuery("/api/v1/orders/" + orderId, "1").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("과거 POINT도 본인 상세만 허용하고 주문 관리자 세 경로는 1번 fixture만 허용한다")
    void protectsCustomerAndAdministratorQueryRoutes() {
        // arrange
        userRepository.save(User.create(1L));
        userRepository.save(User.create(2L));
        com.loopers.domain.ordering.model.Order legacy = com.loopers.domain.ordering.model.Order.create(2L,
            List.of(com.loopers.domain.ordering.model.OrderItem.create(1L, "과거 상품", 1000L, 1)));
        legacy.confirm();
        long orderId = orders.insert(legacy).getId();
        // act / assert
        ResponseEntity<String> other = getQuery("/api/v1/orders/" + orderId, "1");
        ResponseEntity<String> missing = getQuery("/api/v1/orders/999999", "1");
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(other.getBody()).isEqualTo(missing.getBody());
        ResponseEntity<ApiResponse<OrderView>> owner = restTemplate.exchange("/api/v1/orders/" + orderId,
            HttpMethod.GET, new HttpEntity<>(null, headers("2")), new ParameterizedTypeReference<>() {});
        assertThat(owner.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(owner.getBody().data().paymentMethod().name()).isEqualTo("POINT");
        assertThat(owner.getBody().data().paymentAttempt()).isNull();
        assertThat(getQuery("/api/v1/orders/" + orderId, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(getQuery("/api-admin/v1/orders/" + orderId, "1").getStatusCode()).isEqualTo(HttpStatus.OK);
        for (String path : List.of("/api-admin/v1/orders", "/api-admin/v1/orders/" + orderId,
                "/api-admin/v1/payments/incomplete")) {
            assertThat(getQuery(path, "2").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(getQuery(path, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(getQuery(path, "invalid").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(getQuery(path, "9999").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        verify(gateway, never()).create(any(), any(), any());
        verify(gateway, never()).lookup(any());
    }

    @Test
    @DisplayName("고객·관리자·미완료 GET은 PG 상태를 200으로 보여주며 거래·재고·복구 진행을 바꾸지 않는다")
    void exposesPendingAcrossGetRoutesWithoutEffects() {
        // arrange
        userRepository.save(User.create(1L));
        long productId = createProduct("상품", 1000L, 5);
        long orderId = createOrder(1L, List.of(new OrderApiDto.ItemRequest(productId, 2))).getBody().data().orderId();
        sendConfirm(orderId);
        String key = confirmBody(orderId).paymentAttemptKey();
        var before = tasks.findByKey(key).orElseThrow();
        org.mockito.Mockito.clearInvocations(gateway);
        // act / assert
        OrderView customer = findOrder(orderId).getBody().data();
        assertThat(customer.paymentMethod().name()).isEqualTo("PG");
        assertThat(customer.paymentStatus()).isNull();
        assertThat(customer.paymentAttempt().outcome().name()).isEqualTo("PENDING");
        assertThat(findOrders(1L).getBody().data().items().get(0).paymentAttempt()).isEqualTo(customer.paymentAttempt());
        ResponseEntity<ApiResponse<PageResult<AdminOrderView>>> incomplete = restTemplate.exchange(
            "/api-admin/v1/payments/incomplete?size=1", HttpMethod.GET, new HttpEntity<>(null, headers("1")),
            new ParameterizedTypeReference<>() {});
        assertThat(incomplete.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(incomplete.getBody().data().totalElements()).isEqualTo(1);
        assertThat(incomplete.getBody().data().items().get(0).paymentAttempt()).isEqualTo(customer.paymentAttempt());
        assertThat(getQuery("/api-admin/v1/orders/" + orderId, "1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getQuery("/api-admin/v1/orders", "1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getQuery("/api-admin/v1/payments/incomplete?size=0", "1").getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        var after = tasks.findByKey(key).orElseThrow();
        assertThat(after.snapshot()).isEqualTo(before.snapshot());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(currentStock(productId)).isEqualTo(3);
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        verify(gateway, never()).create(any(), any(), any());
        verify(gateway, never()).lookup(any());
    }

    private ResponseEntity<String> getQuery(String path, String userId) {
        return restTemplate.exchange(path, HttpMethod.GET,
            userId == null ? HttpEntity.EMPTY : new HttpEntity<>(null, headers(userId)), String.class);
    }

    @Autowired private PaymentProviderGateway gateway;
    @Autowired private ObjectMapper json;

    @Test
    @DisplayName("정상 PG 생성은 202·주문/결제 상태·조회 경로를 반환하고 포인트를 사용하지 않는다")
    void returnsAcceptedWithPaymentStateAndLookup() {
        userRepository.save(User.create(1L));
        long productId = createProduct("상품", 1_000L, 5);
        long orderId = createOrder(1L, List.of(new OrderApiDto.ItemRequest(productId, 2))).getBody().data().orderId();
        ResponseEntity<ApiResponse<OrderView>> response = sendConfirm(orderId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        OrderView view = response.getBody().data();
        assertThat(view.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(view.paymentMethod().name()).isEqualTo("PG");
        assertThat(view.paymentAttempt().outcome().name()).isEqualTo("PENDING");
        assertThat(view.paymentLookupUrl()).isEqualTo("/api/v1/orders/" + orderId);
        assertThat(view.paymentStatus()).isNull();
        assertThat(currentStock(productId)).isEqualTo(3);
    }

    @Test
    @DisplayName("타인 주문은 없는 주문과 같은 오류로 거절하고 헤더 누락은 400이며 PG·재고를 바꾸지 않는다")
    void protectsConfirmOwnershipAndHeader() {
        userRepository.save(User.create(1L));
        userRepository.save(User.create(2L));
        long productId = createProduct("상품", 1_000L, 5);
        long orderId = createOrder(1L, List.of(new OrderApiDto.ItemRequest(productId, 2))).getBody().data().orderId();
        ResponseEntity<ApiResponse<Object>> other = restTemplate.exchange("/api/v1/orders/" + orderId + "/confirm", HttpMethod.POST,
            new HttpEntity<>(confirmBody(orderId), headers("2")), new ParameterizedTypeReference<>() {});
        ResponseEntity<ApiResponse<Object>> missing = confirmOrderRaw(99999L);
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(other.getBody().meta()).isEqualTo(missing.getBody().meta());
        assertThat(restTemplate.postForEntity("/api/v1/orders/" + orderId + "/confirm", confirmBody(orderId), String.class)
            .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(gateway, never()).create(any(), any(), any());
        assertThat(currentStock(productId)).isEqualTo(5);
    }

    @Test
    @DisplayName("결제 입력의 오타·정수 변환·카드 객체 입력은 원문 노출 없이 400으로 거절한다")
    void rejectsMalformedPaymentInputWithoutEffects() throws Exception {
        userRepository.save(User.create(1L));
        long productId = createProduct("상품", 1_000L, 5);
        long orderId = createOrder(1L, List.of(new OrderApiDto.ItemRequest(productId, 2))).getBody().data().orderId();
        List<Map<String, Object>> options = List.of(Map.of("presett", "NORMAL_SUCCESS"),
            Map.of("overrides", Map.of("processingDelayMs", 7000.9)), Map.of("overrides", Map.of("processingDelayMs", "7000")));
        for (Map<String, Object> option : options) {
            String body = json.writeValueAsString(Map.of("paymentAttemptKey", confirmBody(orderId).paymentAttemptKey(),
                "cardType", "TEST", "cardNo", "0000-0000-0000-0000", "testOptions", option));
            ResponseEntity<String> response = restTemplate.exchange("/api/v1/orders/" + orderId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(body, headers("1")), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        String cardObject = json.writeValueAsString(Map.of("paymentAttemptKey", confirmBody(orderId).paymentAttemptKey(),
            "cardType", "TEST", "cardNo", Map.of("private", "4111-private")));
        ResponseEntity<String> cardError = restTemplate.exchange("/api/v1/orders/" + orderId + "/confirm", HttpMethod.POST,
            new HttpEntity<>(cardObject, headers("1")), String.class);
        assertThat(cardError.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(cardError.getBody()).doesNotContain("4111-private");
        verify(gateway, never()).create(any(), any(), any());
        assertThat(currentStock(productId)).isEqualTo(5);
        assertThat(orderCount()).isEqualTo(1L);
    }
    @Autowired private PgPaymentTestSupport pg;
    @BeforeEach void stubPg() { pg.usePendingProvider(); }
    private static final QOrderJpaEntity ORDER = QOrderJpaEntity.orderJpaEntity;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private DeleteBrandUseCase deleteBrandUseCase;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private WalletRepository walletRepository;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("주문 생성")
    @Nested
    class Create {
        @DisplayName("같은 상품이 중복되면 수량을 합산해 DRAFT로 생성한다")
        @Test
        void createsDraftOrder_withMergedQuantities() {
            userRepository.save(User.create(1L));
            long productId = createProduct("상품", 1_000L, 10);

            ResponseEntity<ApiResponse<OrderView>> response = createOrder(1L, List.of(
                new OrderApiDto.ItemRequest(productId, 2),
                new OrderApiDto.ItemRequest(productId, 3)
            ));

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED),
                () -> assertThat(response.getBody().data().status()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(response.getBody().data().totalAmount()).isEqualTo(5_000L),
                () -> assertThat(response.getBody().data().items()).hasSize(1),
                () -> assertThat(response.getBody().data().items().get(0).quantity()).isEqualTo(5),
                () -> assertThat(response.getBody().data().paymentAmount()).isNull(),
                () -> assertThat(currentStock(productId)).isEqualTo(10)
            );
        }

        @DisplayName("삭제된 상품이 포함되면 404를 반환하고 주문을 생성하지 않는다")
        @Test
        void returnsNotFound_whenProductIsDeleted() {
            userRepository.save(User.create(1L));
            long productId = createProduct("상품", 1_000L, 10);
            Product product = productRepository.findById(productId).orElseThrow();
            product.delete();
            productRepository.save(product);

            ResponseEntity<ApiResponse<Object>> response = createOrderRaw(1L,
                List.of(new OrderApiDto.ItemRequest(productId, 1)));

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(orderCount()).isZero()
            );
        }

        @DisplayName("품목이 비어 있으면 400을 반환한다")
        @Test
        void returnsBadRequest_whenItemsIsEmpty() {
            userRepository.save(User.create(1L));

            ResponseEntity<ApiResponse<Object>> response = createOrderRaw(1L, List.of());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @DisplayName("주문 확정")
    @Nested
    class Confirm {
        @DisplayName("PG 대기를 거쳐 저장된 성공을 반영하면 200과 PG 결제 결과를 반환한다")
        @Test
        void confirmsOrder_andReturnsPaymentResult() {
            userRepository.save(User.create(1L));
            long productId = createProduct("상품", 1_000L, 10);
            chargePoint(1L, 10_000L);
            long orderId = createOrder(1L, List.of(new OrderApiDto.ItemRequest(productId, 2)))
                .getBody().data().orderId();

            ResponseEntity<ApiResponse<OrderView>> response = confirmOrder(orderId);

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().status()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(response.getBody().data().paymentAmount()).isEqualTo(2_000L),
                () -> assertThat(response.getBody().data().paymentStatus()).isEqualTo(OrderRecordStatus.PAID)
            );
        }

        @DisplayName("확정 후 고객·관리자 조회에서도 결제 결과가 노출된다")
        @Test
        void exposesPaymentResult_inCustomerAndAdminQueries() {
            userRepository.save(User.create(1L));
            long productId = createProduct("상품", 1_000L, 10);
            chargePoint(1L, 10_000L);
            long orderId = createOrder(1L, List.of(new OrderApiDto.ItemRequest(productId, 2)))
                .getBody().data().orderId();
            confirmOrder(orderId);

            ResponseEntity<ApiResponse<OrderView>> customerDetail = findOrder(orderId);
            ResponseEntity<ApiResponse<AdminOrderView>> adminDetail = restTemplate.exchange(
                "/api-admin/v1/orders/" + orderId,
                HttpMethod.GET,
                new HttpEntity<>(null, headers("1")),
                new ParameterizedTypeReference<>() {}
            );

            assertAll(
                () -> assertThat(customerDetail.getBody().data().paymentAmount()).isEqualTo(2_000L),
                () -> assertThat(customerDetail.getBody().data().paymentStatus()).isEqualTo(OrderRecordStatus.PAID),
                () -> assertThat(adminDetail.getBody().data().paymentAmount()).isEqualTo(2_000L),
                () -> assertThat(adminDetail.getBody().data().paymentStatus()).isEqualTo(OrderRecordStatus.PAID)
            );
        }

        @DisplayName("없는 주문은 404를 반환한다")
        @Test
        void returnsNotFound_whenOrderDoesNotExist() {
            userRepository.save(User.create(1L));
            ResponseEntity<ApiResponse<Object>> response = confirmOrderRaw(999L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("브랜드 일괄 삭제 후에도 과거 주문의 금액·결제 결과는 보존된다")
        @Test
        void preservesPaymentResult_afterBrandBulkDelete() {
            userRepository.save(User.create(1L));
            Brand brand = brandRepository.save(Brand.create("브랜드", null));
            Product product = productRepository.save(Product.create(brand.getId(), "상품", null, 1_000L, 10));
            chargePoint(1L, 10_000L);
            long orderId = createOrder(1L, List.of(new OrderApiDto.ItemRequest(product.getId(), 2)))
                .getBody().data().orderId();
            confirmOrder(orderId);

            deleteBrandUseCase.execute(new BrandCommand.Delete(brand.getId()));

            ResponseEntity<ApiResponse<OrderView>> customerDetail = findOrder(orderId);
            ResponseEntity<ApiResponse<AdminOrderView>> adminDetail = restTemplate.exchange(
                "/api-admin/v1/orders/" + orderId,
                HttpMethod.GET,
                new HttpEntity<>(null, headers("1")),
                new ParameterizedTypeReference<>() {}
            );

            assertAll(
                () -> assertThat(customerDetail.getBody().data().totalAmount()).isEqualTo(2_000L),
                () -> assertThat(customerDetail.getBody().data().paymentAmount()).isEqualTo(2_000L),
                () -> assertThat(customerDetail.getBody().data().paymentStatus()).isEqualTo(OrderRecordStatus.PAID),
                () -> assertThat(adminDetail.getBody().data().paymentAmount()).isEqualTo(2_000L),
                () -> assertThat(adminDetail.getBody().data().paymentStatus()).isEqualTo(OrderRecordStatus.PAID)
            );
        }

        @DisplayName("재고가 부족하면 409를 반환한다")
        @Test
        void returnsConflict_whenStockIsInsufficient() {
            userRepository.save(User.create(1L));
            long productId = createProduct("상품", 1_000L, 1);
            chargePoint(1L, 10_000L);
            long orderId = createOrder(1L, List.of(new OrderApiDto.ItemRequest(productId, 2)))
                .getBody().data().orderId();

            ResponseEntity<ApiResponse<Object>> response = confirmOrderRaw(orderId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @DisplayName("포인트가 없어도 PG 결제 대기는 202로 반환한다")
        @Test
        void returnsConflict_whenPointIsInsufficient() {
            userRepository.save(User.create(1L));
            walletRepository.save(Wallet.zero(1L));
            long productId = createProduct("상품", 1_000L, 10);
            long orderId = createOrder(1L, List.of(new OrderApiDto.ItemRequest(productId, 2)))
                .getBody().data().orderId();

            ResponseEntity<ApiResponse<Object>> response = confirmOrderRaw(orderId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }

        @DisplayName("같은 완료 PG 요청은 추가 결제 없이 200을 반환한다")
        @Test
        void returnsConflict_whenAlreadyConfirmed() {
            userRepository.save(User.create(1L));
            long productId = createProduct("상품", 1_000L, 10);
            chargePoint(1L, 10_000L);
            long orderId = createOrder(1L, List.of(new OrderApiDto.ItemRequest(productId, 2)))
                .getBody().data().orderId();
            confirmOrder(orderId);

            ResponseEntity<ApiResponse<Object>> response = confirmOrderRaw(orderId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @DisplayName("브랜드 일괄 삭제로 상품이 삭제된 뒤 확정하면 404를 반환한다")
        @Test
        void returnsNotFound_whenProductDeletedViaBrandBulkDelete() {
            // arrange
            userRepository.save(User.create(1L));
            Brand brand = brandRepository.save(Brand.create("브랜드", null));
            Product product = productRepository.save(Product.create(brand.getId(), "상품", null, 1_000L, 10));
            chargePoint(1L, 10_000L);
            long orderId = createOrder(1L, List.of(new OrderApiDto.ItemRequest(product.getId(), 2)))
                .getBody().data().orderId();

            deleteBrandUseCase.execute(new BrandCommand.Delete(brand.getId()));

            // act
            ResponseEntity<ApiResponse<Object>> response = confirmOrderRaw(orderId);

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(response.getBody().meta().errorCode()).isEqualTo("Not Found")
            );
        }
    }

    @DisplayName("내 주문 목록·상세")
    @Nested
    class CustomerQuery {
        @DisplayName("헤더로 지정한 사용자의 주문만 조회한다")
        @Test
        void returnsOnlyOwnOrders() {
            userRepository.save(User.create(1L));
            userRepository.save(User.create(2L));
            long productId = createProduct("상품", 1_000L, 10);
            createOrder(1L, List.of(new OrderApiDto.ItemRequest(productId, 1)));
            createOrder(2L, List.of(new OrderApiDto.ItemRequest(productId, 1)));

            ResponseEntity<ApiResponse<PageResult<OrderView>>> response = findOrders(1L);

            assertThat(response.getBody().data().items()).hasSize(1);
        }

        @DisplayName("없는 주문은 404를 반환한다")
        @Test
        void returnsNotFound_whenOrderDoesNotExist() {
            userRepository.save(User.create(1L));
            ResponseEntity<ApiResponse<Object>> response = restTemplate.exchange(
                "/api/v1/orders/999",
                HttpMethod.GET,
                new HttpEntity<>(null, headers("1")),
                new ParameterizedTypeReference<>() {}
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("관리자 주문 목록·상세")
    @Nested
    class AdminQuery {
        @DisplayName("모든 구매자의 주문을 구매자 ID와 함께 조회한다")
        @Test
        void returnsAllOrders_withUserId() {
            userRepository.save(User.create(1L));
            long productId = createProduct("상품", 1_000L, 10);
            createOrder(1L, List.of(new OrderApiDto.ItemRequest(productId, 1)));

            ResponseEntity<ApiResponse<PageResult<AdminOrderView>>> response = restTemplate.exchange(
                "/api-admin/v1/orders",
                HttpMethod.GET,
                new HttpEntity<>(null, headers("1")),
                new ParameterizedTypeReference<>() {}
            );

            assertAll(
                () -> assertThat(response.getBody().data().items()).hasSize(1),
                () -> assertThat(response.getBody().data().items().get(0).userId()).isEqualTo(1L)
            );
        }

        @DisplayName("없는 주문은 404를 반환한다")
        @Test
        void returnsNotFound_whenOrderDoesNotExist() {
            userRepository.save(User.create(1L));
            ResponseEntity<ApiResponse<Object>> response = restTemplate.exchange(
                "/api-admin/v1/orders/999",
                HttpMethod.GET,
                new HttpEntity<>(null, headers("1")),
                new ParameterizedTypeReference<>() {}
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    private long createProduct(String name, long price, int stock) {
        Brand brand = brandRepository.save(Brand.create("브랜드", null));
        Product product = productRepository.save(Product.create(brand.getId(), name, null, price, stock));
        return product.getId();
    }

    private int currentStock(long productId) {
        return productRepository.findById(productId).orElseThrow().getStock();
    }

    private void chargePoint(long userId, long amount) {
        Wallet wallet = walletRepository.save(Wallet.zero(userId));
        wallet.charge(Money.positive(amount));
        walletRepository.save(wallet);
    }

    private ResponseEntity<ApiResponse<OrderView>> confirmOrder(long orderId) {
        ResponseEntity<ApiResponse<OrderView>> first = sendConfirm(orderId);
        if (first.getStatusCode() != HttpStatus.ACCEPTED) { return first; }
        pg.complete(orderId);
        return sendConfirm(orderId);
    }

    private ResponseEntity<ApiResponse<OrderView>> sendConfirm(long orderId) {
        return restTemplate.exchange("/api/v1/orders/" + orderId + "/confirm", HttpMethod.POST,
            new HttpEntity<>(confirmBody(orderId), headers("1")), new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<ApiResponse<Object>> confirmOrderRaw(long orderId) {
        return restTemplate.exchange("/api/v1/orders/" + orderId + "/confirm", HttpMethod.POST,
            new HttpEntity<>(confirmBody(orderId), headers("1")), new ParameterizedTypeReference<>() {});
    }

    private OrderApiDto.ConfirmRequest confirmBody(long orderId) {
        return new OrderApiDto.ConfirmRequest(PgPaymentTestSupport.command(1L, orderId).paymentAttemptKey(),
            "TEST", "0000-0000-0000-0000", null);
    }
    private long orderCount() {
        return queryFactory.select(ORDER.count()).from(ORDER).fetchOne();
    }

    private ResponseEntity<ApiResponse<OrderView>> createOrder(long userId, List<OrderApiDto.ItemRequest> items) {
        return restTemplate.exchange(
            "/api/v1/orders",
            HttpMethod.POST,
            new HttpEntity<>(new OrderApiDto.CreateRequest(items), headers(String.valueOf(userId))),
            new ParameterizedTypeReference<>() {}
        );
    }

    private ResponseEntity<ApiResponse<Object>> createOrderRaw(long userId, List<OrderApiDto.ItemRequest> items) {
        return restTemplate.exchange(
            "/api/v1/orders",
            HttpMethod.POST,
            new HttpEntity<>(new OrderApiDto.CreateRequest(items), headers(String.valueOf(userId))),
            new ParameterizedTypeReference<>() {}
        );
    }

    private ResponseEntity<ApiResponse<PageResult<OrderView>>> findOrders(long userId) {
        return restTemplate.exchange(
            "/api/v1/orders",
            HttpMethod.GET,
            new HttpEntity<>(null, headers(String.valueOf(userId))),
            new ParameterizedTypeReference<>() {}
        );
    }

    private ResponseEntity<ApiResponse<OrderView>> findOrder(long orderId) {
        return restTemplate.exchange(
            "/api/v1/orders/" + orderId,
            HttpMethod.GET,
            new HttpEntity<>(null, headers("1")),
            new ParameterizedTypeReference<>() {}
        );
    }

    private HttpHeaders headers(String userIdHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.add("X-USER-ID", userIdHeader);
        return headers;
    }
}
