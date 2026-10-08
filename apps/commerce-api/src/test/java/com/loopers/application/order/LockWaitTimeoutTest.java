package com.loopers.application.order;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.point.PointFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.point.ChargeAmount;
import com.loopers.domain.product.Price;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest
class LockWaitTimeoutTest {

    private static final Long BUYER = 1L;
    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
    private static final int INITIAL_STOCK = 10;
    private static final long GIVE_UP_SECONDS = 10;

    private final OrderFacade orderFacade;
    private final ProductFacade productFacade;
    private final BrandFacade brandFacade;
    private final PointFacade pointFacade;
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    private Long coat;
    private Long knit;

    @Autowired
    LockWaitTimeoutTest(
        OrderFacade orderFacade,
        ProductFacade productFacade,
        BrandFacade brandFacade,
        PointFacade pointFacade,
        DataSource dataSource,
        JdbcTemplate jdbcTemplate,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.orderFacade = orderFacade;
        this.productFacade = productFacade;
        this.brandFacade = brandFacade;
        this.pointFacade = pointFacade;
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        coat = productFacade.register(brandId, "코트", Price.of(10_000)).getId();
        knit = productFacade.register(brandId, "니트", Price.of(5_000)).getId();
        productFacade.adjustStock(coat, Quantity.of(INITIAL_STOCK));
        productFacade.adjustStock(knit, Quantity.of(INITIAL_STOCK));
        pointFacade.charge(BUYER, ChargeAmount.of(50_000), NOW);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("ORDER-015 · 다른 트랜잭션이 둘째 품목을 쥐고 있으면, 확정은 잠금 대기 한도에서 포기하고 결제와 첫 품목 차감까지 전부 되돌린다")
    @Test
    void givesUpOnLockWaitAndRollsBackEverything() throws Exception {
        Long orderId = orderFacade.place(new OrderCreateCommand(BUYER, List.of(
            new OrderCreateCommand.Line(coat, OrderQuantity.of(2)),
            new OrderCreateCommand.Line(knit, OrderQuantity.of(1))
        )), NOW).getId();

        Throwable failure;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM product WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, knit);
                lock.executeQuery().close();
            }
            try {
                Future<?> confirm = executor.submit(() -> orderFacade.confirm(BUYER, orderId, NOW));
                failure = catchThrowable(() -> confirm.get(GIVE_UP_SECONDS, TimeUnit.SECONDS));
            } finally {
                holder.rollback();
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(GIVE_UP_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(failure).as("50 초를 기다리지 않고 제한 안에 포기한다").isInstanceOf(ExecutionException.class);
        assertThat(failure.getCause()).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(balance()).isEqualTo(50_000L);
        assertThat(quantityOf(coat)).isEqualTo(INITIAL_STOCK);
        assertThat(quantityOf(knit)).isEqualTo(INITIAL_STOCK);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
            .isEqualTo("DRAFT");
    }

    private long balance() {
        Long value = jdbcTemplate.queryForObject("SELECT balance FROM user_point WHERE user_id = ?", Long.class, BUYER);
        return value == null ? -1 : value;
    }

    private int quantityOf(Long productId) {
        Integer value = jdbcTemplate.queryForObject("SELECT quantity FROM product WHERE id = ?", Integer.class, productId);
        return value == null ? -1 : value;
    }
}
