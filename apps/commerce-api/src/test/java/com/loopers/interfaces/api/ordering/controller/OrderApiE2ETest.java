package com.loopers.interfaces.api.ordering.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.loopers.application.common.PageResult;
import com.loopers.application.mall.command.BrandCommand;
import com.loopers.application.mall.usecase.DeleteBrandUseCase;
import com.loopers.application.ordering.query.AdminOrderView;
import com.loopers.application.ordering.query.OrderView;
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
        @DisplayName("재고·포인트가 충분하면 200과 결제 결과를 반환한다")
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
                HttpEntity.EMPTY,
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
                HttpEntity.EMPTY,
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

        @DisplayName("포인트가 부족하면 409를 반환한다")
        @Test
        void returnsConflict_whenPointIsInsufficient() {
            userRepository.save(User.create(1L));
            walletRepository.save(Wallet.zero(1L));
            long productId = createProduct("상품", 1_000L, 10);
            long orderId = createOrder(1L, List.of(new OrderApiDto.ItemRequest(productId, 2)))
                .getBody().data().orderId();

            ResponseEntity<ApiResponse<Object>> response = confirmOrderRaw(orderId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @DisplayName("이미 확정된 주문을 다시 확정하면 409를 반환한다")
        @Test
        void returnsConflict_whenAlreadyConfirmed() {
            userRepository.save(User.create(1L));
            long productId = createProduct("상품", 1_000L, 10);
            chargePoint(1L, 10_000L);
            long orderId = createOrder(1L, List.of(new OrderApiDto.ItemRequest(productId, 2)))
                .getBody().data().orderId();
            confirmOrder(orderId);

            ResponseEntity<ApiResponse<Object>> response = confirmOrderRaw(orderId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
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
            ResponseEntity<ApiResponse<Object>> response = restTemplate.exchange(
                "/api/v1/orders/999",
                HttpMethod.GET,
                HttpEntity.EMPTY,
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
                HttpEntity.EMPTY,
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
            ResponseEntity<ApiResponse<Object>> response = restTemplate.exchange(
                "/api-admin/v1/orders/999",
                HttpMethod.GET,
                HttpEntity.EMPTY,
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
        return restTemplate.exchange(
            "/api/v1/orders/" + orderId + "/confirm",
            HttpMethod.POST,
            HttpEntity.EMPTY,
            new ParameterizedTypeReference<>() {}
        );
    }

    private ResponseEntity<ApiResponse<Object>> confirmOrderRaw(long orderId) {
        return restTemplate.exchange(
            "/api/v1/orders/" + orderId + "/confirm",
            HttpMethod.POST,
            HttpEntity.EMPTY,
            new ParameterizedTypeReference<>() {}
        );
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
            HttpEntity.EMPTY,
            new ParameterizedTypeReference<>() {}
        );
    }

    private HttpHeaders headers(String userIdHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-USER-ID", userIdHeader);
        return headers;
    }
}
