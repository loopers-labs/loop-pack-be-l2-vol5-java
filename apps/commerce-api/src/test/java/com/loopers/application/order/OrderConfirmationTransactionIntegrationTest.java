package com.loopers.application.order;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointBalance;
import com.loopers.domain.point.PointRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
class OrderConfirmationTransactionIntegrationTest {

    private static final long INITIAL_FIRST_STOCK = 5L;
    private static final long INITIAL_SECOND_STOCK = 4L;
    private static final long INITIAL_POINT_BALANCE = 1_000L;
    private static final long TOTAL_AMOUNT = 500L;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @MockitoSpyBean
    private PointRepository pointRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    void confirmsMultiItemDraftAndCommitsStockPointAndPaymentTogether() {
        Fixture fixture = createFixture();

        orderFacade.confirm(fixture.userId(), fixture.orderId());

        entityManager.clear();
        Order confirmedOrder = orderRepository.findById(fixture.orderId()).orElseThrow();
        Product firstProduct = productRepository.findById(fixture.firstProductId()).orElseThrow();
        Product secondProduct = productRepository.findById(fixture.secondProductId()).orElseThrow();
        Point point = pointRepository.findByUserId(fixture.userId()).orElseThrow();

        assertThat(confirmedOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmedOrder.getTotalAmount()).isEqualTo(TOTAL_AMOUNT);
        assertThat(confirmedOrder.getPaymentAmount()).isEqualTo(TOTAL_AMOUNT);
        assertThat(confirmedOrder.getPaymentResult().name()).isEqualTo("SUCCESS");
        assertThat(confirmedOrder.getItems()).hasSize(2);
        assertThat(firstProduct.getStock().amount()).isEqualTo(3L);
        assertThat(secondProduct.getStock().amount()).isEqualTo(3L);
        assertThat(point.getBalance().amount()).isEqualTo(500L);
    }

    @Test
    void rollsBackAllDatabaseChangesWhenFailureOccursAfterConfirmationWrites() {
        Fixture fixture = createFixture();
        AtomicReference<DatabaseState> stateBeforeFailure = new AtomicReference<>();

        doAnswer(invocation -> {
            int updatedRows = (int) invocation.callRealMethod();
            assertThat(updatedRows).isEqualTo(1);
            entityManager.flush();
            stateBeforeFailure.set(readDatabaseState(fixture));
            throw new IllegalStateException("포인트 차감 변경 후 실패 주입");
        }).when(pointRepository).decreaseBalanceIfEnough(
            eq(fixture.userId()), eq(TOTAL_AMOUNT), any(ZonedDateTime.class)
        );

        assertThatThrownBy(() -> orderFacade.confirm(fixture.userId(), fixture.orderId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("포인트 차감 변경 후 실패 주입");

        assertThat(stateBeforeFailure.get()).isEqualTo(new DatabaseState(
            3L,
            3L,
            500L,
            "CONFIRMED",
            TOTAL_AMOUNT,
            "SUCCESS"
        ));

        entityManager.clear();
        Order orderAfterRollback = orderRepository.findById(fixture.orderId()).orElseThrow();
        Product firstProductAfterRollback = productRepository.findById(fixture.firstProductId()).orElseThrow();
        Product secondProductAfterRollback = productRepository.findById(fixture.secondProductId()).orElseThrow();
        Point pointAfterRollback = pointRepository.findByUserId(fixture.userId()).orElseThrow();

        assertThat(orderAfterRollback.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(orderAfterRollback.getTotalAmount()).isEqualTo(TOTAL_AMOUNT);
        assertThat(orderAfterRollback.getPaymentAmount()).isNull();
        assertThat(orderAfterRollback.getPaymentResult()).isNull();
        assertThat(orderAfterRollback.getItems()).hasSize(2);
        assertThat(firstProductAfterRollback.getStock().amount()).isEqualTo(INITIAL_FIRST_STOCK);
        assertThat(secondProductAfterRollback.getStock().amount()).isEqualTo(INITIAL_SECOND_STOCK);
        assertThat(pointAfterRollback.getBalance().amount()).isEqualTo(INITIAL_POINT_BALANCE);
    }

    private Fixture createFixture() {
        User user = userRepository.save(User.create());
        Brand brand = brandRepository.save(Brand.create("Nike"));

        Product firstProduct = Product.create(brand.getId(), "Air Max", 100L);
        firstProduct.changeStockTo(INITIAL_FIRST_STOCK);
        firstProduct = productRepository.save(firstProduct);

        Product secondProduct = Product.create(brand.getId(), "Pegasus", 300L);
        secondProduct.changeStockTo(INITIAL_SECOND_STOCK);
        secondProduct = productRepository.save(secondProduct);

        pointRepository.save(Point.create(user.getId(), new PointBalance(INITIAL_POINT_BALANCE)));

        Order order = Order.create(user.getId(), List.of(
            OrderItem.create(firstProduct.getId(), firstProduct.getName(), firstProduct.getPrice(), 2),
            OrderItem.create(secondProduct.getId(), secondProduct.getName(), secondProduct.getPrice(), 1)
        ));
        order = orderRepository.save(order);

        return new Fixture(user.getId(), firstProduct.getId(), secondProduct.getId(), order.getId());
    }

    private DatabaseState readDatabaseState(Fixture fixture) {
        long firstStock = jdbcTemplate.queryForObject(
            "SELECT stock FROM products WHERE id = ?",
            Long.class,
            fixture.firstProductId()
        );
        long secondStock = jdbcTemplate.queryForObject(
            "SELECT stock FROM products WHERE id = ?",
            Long.class,
            fixture.secondProductId()
        );
        long pointBalance = jdbcTemplate.queryForObject(
            "SELECT balance FROM points WHERE user_id = ?",
            Long.class,
            fixture.userId()
        );
        Map<String, Object> order = jdbcTemplate.queryForMap(
            "SELECT status, payment_amount, payment_result FROM orders WHERE id = ?",
            fixture.orderId()
        );

        return new DatabaseState(
            firstStock,
            secondStock,
            pointBalance,
            (String) order.get("status"),
            ((Number) order.get("payment_amount")).longValue(),
            (String) order.get("payment_result")
        );
    }

    private record Fixture(Long userId, Long firstProductId, Long secondProductId, Long orderId) {}

    private record DatabaseState(
        long firstStock,
        long secondStock,
        long pointBalance,
        String orderStatus,
        long paymentAmount,
        String paymentResult
    ) {}
}
