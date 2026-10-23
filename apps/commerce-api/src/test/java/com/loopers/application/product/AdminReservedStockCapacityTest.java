package com.loopers.application.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderItemReservationStatus;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductListRevisionRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductStock;
import com.loopers.domain.product.ProductStockException;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.AdminMockMvc;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class AdminReservedStockCapacityTest {

    @TestBean(methodName = "com.loopers.infrastructure.payment.support.PaymentFingerprintTestConfiguration#create")
    private PaymentRequestFingerprinter fingerprinter;

    @Autowired private MockMvc mvc;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private UserRepository users;
    @Autowired private OrderRepository orders;
    @Autowired private ProductListRevisionRepository revisions;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;

    @AfterEach
    void clean() {
        cleanup.truncateAllTables();
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "2147483647, 0", "2147483642, 5", "0, 2147483647"})
    void capacityIncludesTheExactIntegerBoundary(int available, long held) {
        assertThatCode(() -> ProductStock.validateCapacity(available, held)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"2147483643, 5", "0, 2147483648", "1, 9223372036854775807"})
    void capacityRejectsOverflowWithoutOverflowingItsOwnCalculation(int available, long held) {
        assertThatThrownBy(() -> ProductStock.validateCapacity(available, held))
            .isInstanceOfSatisfying(ProductStockException.class,
                error -> assertThat(error.getReason()).isEqualTo(ProductStockException.Reason.STOCK_CAPACITY_EXCEEDED));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE})
    void negativeAvailableStockKeepsItsExistingInputError(int available) {
        assertThatThrownBy(() -> ProductStock.validateCapacity(available, 5L))
            .isInstanceOfSatisfying(ProductStockException.class,
                error -> assertThat(error.getReason()).isEqualTo(ProductStockException.Reason.INVALID_STOCK_QUANTITY));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, Long.MIN_VALUE})
    void rejectsCorruptedNegativeReservationTotals(long held) {
        assertThatThrownBy(() -> ProductStock.validateCapacity(0, held)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sumsAllHeldOrdersForOnlyTheRequestedProduct() {
        Fixture fixture = fixture();

        long held = tx().execute(status -> {
            products.lockById(fixture.productId()).orElseThrow();
            return orders.sumHeldQuantityByProductId(fixture.productId());
        });
        long otherHeld = tx().execute(status -> {
            products.lockById(fixture.otherProductId()).orElseThrow();
            return orders.sumHeldQuantityByProductId(fixture.otherProductId());
        });

        assertThat(held).isEqualTo(5L);
        assertThat(otherHeld).isEqualTo(8L);
        long missingHeld = tx().execute(status -> orders.sumHeldQuantityByProductId(Long.MAX_VALUE));
        assertThat(missingHeld).isEqualTo(0L);
    }

    @Test
    void acceptsExactCapacityAndPreservesEveryReservation() {
        Fixture fixture = fixture();
        DatabaseState before = databaseState();

        ResponseEntity<JsonNode> response = changeStock(fixture.productId(), "admin", Integer.MAX_VALUE - 5);

        assertSuccess(response, fixture.productId(), Integer.MAX_VALUE - 5);
        assertThat(stock(fixture.productId())).isEqualTo(Integer.MAX_VALUE - 5);
        assertThat(stock(fixture.otherProductId())).isEqualTo(92);
        DatabaseState after = databaseState();
        assertThat(after.orders()).isEqualTo(before.orders());
        assertThat(after.items()).isEqualTo(before.items());
        assertThat(revisions.current()).isEqualTo(before.revision() + 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MAX_VALUE - 4, Integer.MAX_VALUE})
    void rejectsInsufficientReturnCapacityWithAnExplicitConflictAndNoDbChanges(int proposed) {
        Fixture fixture = fixture();
        DatabaseState before = databaseState();

        ResponseEntity<JsonNode> response = changeStock(fixture.productId(), "admin", proposed);

        assertError(response, 409, "STOCK_CAPACITY_EXCEEDED", "예약 반환 수량을 포함하면 재고 저장 한도를 초과합니다.");
        assertThat(databaseState()).isEqualTo(before);
    }

    @Test
    void negativeHttpInputRemainsBadRequestAndDoesNotChangeAnyReservation() {
        Fixture fixture = fixture();
        DatabaseState before = databaseState();

        ResponseEntity<JsonNode> response = changeStock(fixture.productId(), "admin", -1);

        assertError(response, 400, "INVALID_REQUEST", "요청 값이 올바르지 않습니다.");
        assertThat(databaseState()).isEqualTo(before);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"alice"})
    void authorizationPrecedesCapacityAndInputValidation(String requester) {
        fixture();
        DatabaseState before = databaseState();

        ResponseEntity<JsonNode> response = AdminMockMvc.exchange(mvc, HttpMethod.PUT,
            "/api-admin/v1/products/abc/stock", requester, "{broken");

        assertError(response, 403, "FORBIDDEN", "관리자 권한이 필요합니다.");
        assertThat(databaseState()).isEqualTo(before);
    }

    @Test
    void aConsumedReservationNoLongerReservesReturnCapacity() {
        Fixture fixture = fixture();
        tx().executeWithoutResult(status -> {
            List<Order> heldOrders = fixture.heldOrderIds().stream()
                .map(id -> orders.lockById(id).orElseThrow()).toList();
            products.lockById(fixture.productId()).orElseThrow();
            heldOrders.forEach(order -> order.confirmPgPayment(ZonedDateTime.parse("2026-10-23T00:00:00Z")));
        });
        DatabaseState before = databaseState();

        ResponseEntity<JsonNode> response = changeStock(fixture.productId(), "admin", Integer.MAX_VALUE);

        assertSuccess(response, fixture.productId(), Integer.MAX_VALUE);
        assertThat(stock(fixture.productId())).isEqualTo(Integer.MAX_VALUE);
        assertThat(databaseState().orders()).isEqualTo(before.orders());
        assertThat(databaseState().items()).isEqualTo(before.items());
        long remainingHeld = tx().execute(status -> orders.sumHeldQuantityByProductId(fixture.productId()));
        assertThat(remainingHeld).isEqualTo(0L);
    }

    private Fixture fixture() {
        return tx().execute(status -> {
            Brand brand = brands.save(new Brand("브랜드"));
            User user = users.save(new User(7101L));
            Product product = products.save(new Product(brand, "상품", 1000, 100));
            Product other = products.save(new Product(brand, "다른 상품", 1000, 100));
            Order heldTwo = orderFixture(user, product, 2, OrderItemReservationStatus.HELD);
            Order heldThree = orderFixture(user, product, 3, OrderItemReservationStatus.HELD);
            orderFixture(user, product, 4, OrderItemReservationStatus.CONSUMED);
            orderFixture(user, product, 6, OrderItemReservationStatus.RELEASED);
            orderFixture(user, product, 7, OrderItemReservationStatus.NOT_RESERVED);
            orderFixture(user, other, 8, OrderItemReservationStatus.HELD);
            revisions.advance();
            return new Fixture(product.getId(), other.getId(), List.of(heldTwo.getId(), heldThree.getId()));
        });
    }

    private Order orderFixture(User user, Product product, int quantity, OrderItemReservationStatus state) {
        Order order = orders.save(Order.create(user, List.of(new OrderItem(product, quantity))));
        if (state != OrderItemReservationStatus.NOT_RESERVED) {
            order.preparePgPayment();
            product.deductStock(quantity);
            if (state == OrderItemReservationStatus.CONSUMED) {
                order.confirmPgPayment(ZonedDateTime.parse("2026-10-23T00:00:00Z"));
            } else if (state == OrderItemReservationStatus.RELEASED) {
                order.failPgPayment();
                product.returnReservedStock(quantity);
            }
        }
        return order;
    }

    private TransactionTemplate tx() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    private ResponseEntity<JsonNode> changeStock(long productId, String requester, int quantity) {
        return AdminMockMvc.exchange(mvc, HttpMethod.PUT, "/api-admin/v1/products/" + productId + "/stock",
            requester, "{\"stockQuantity\":" + quantity + "}");
    }

    private void assertSuccess(ResponseEntity<JsonNode> response, long productId, int quantity) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("SUCCESS");
        assertThat(response.getBody().path("meta").has("errorCode")).isFalse();
        assertThat(response.getBody().path("data").path("productId").asLong()).isEqualTo(productId);
        assertThat(response.getBody().path("data").path("stockQuantity").asInt()).isEqualTo(quantity);
    }

    private void assertError(ResponseEntity<JsonNode> response, int status, String code, String message) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().path("meta").path("result").asText()).isEqualTo("FAIL");
        assertThat(response.getBody().path("meta").path("errorCode").asText()).isEqualTo(code);
        assertThat(response.getBody().path("meta").path("message").asText()).isEqualTo(message);
        assertThat(response.getBody().has("data")).isFalse();
    }

    private int stock(long productId) {
        return jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id = ?", Integer.class, productId);
    }

    private DatabaseState databaseState() {
        return new DatabaseState(jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            jdbc.queryForList("SELECT * FROM order_item ORDER BY id"), revisions.current());
    }

    private record Fixture(long productId, long otherProductId, List<Long> heldOrderIds) {
    }

    private record DatabaseState(List<Map<String, Object>> products, List<Map<String, Object>> orders,
                                 List<Map<String, Object>> items, long revision) {
    }
}
