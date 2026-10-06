package com.loopers.interfaces.api.order;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.order.OrderApplicationService;
import com.loopers.application.order.OrderResult;
import com.loopers.application.point.PointApplicationService;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.infrastructure.user.UserJpaEntity;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LockTimeoutTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BrandApplicationService brandApplicationService;

    @Autowired
    private ProductApplicationService productApplicationService;

    @Autowired
    private PointApplicationService pointApplicationService;

    @Autowired
    private OrderApplicationService orderApplicationService;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private long productId;

    private long orderId;

    @BeforeEach
    void prepare() {
        userJpaRepository.save(new UserJpaEntity(1));
        long brandId = brandApplicationService.create("브랜드").id().value();
        productId = productApplicationService.create(brandId, "상품", 3000, 5).id();
        pointApplicationService.charge(1, 10000);
        OrderResult order = orderApplicationService.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1)));
        orderId = order.id();
    }

    @AfterEach
    void clean() {
        jdbcTemplate.update("delete from order_items");
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("다른 트랜잭션이 상품 행을 잠근 동안 주문을 확정하면 약 3초 뒤 503으로 실패하고 주문·재고·잔액을 유지하며, 잠금이 풀리면 같은 요청이 성공한다")
    void failsWith503WhenLockWaitExceeded() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.queryForObject("select stock from products where id = ? for update", Integer.class, productId);
                locked.countDown();
                awaitRelease(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            long startedAt = System.nanoTime();
            mockMvc.perform(post("/api/v1/orders/{id}/confirm", orderId).header("X-USER-ID", "1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.meta.result").value("FAIL"));
            Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);
            assertThat(waited).isBetween(Duration.ofMillis(2500), Duration.ofSeconds(10));

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        assertThat(orderApplicationService.getMyOrder(1, orderId).status()).isEqualTo("DRAFT");
        assertThat(productApplicationService.getAdminProduct(productId).stock()).isEqualTo(5);
        assertThat(pointApplicationService.balance(1)).isEqualTo(10000);

        mockMvc.perform(post("/api/v1/orders/{id}/confirm", orderId).header("X-USER-ID", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        assertThat(productApplicationService.getAdminProduct(productId).stock()).isEqualTo(4);
        assertThat(pointApplicationService.balance(1)).isEqualTo(7000);
    }

    private void awaitRelease(CountDownLatch release) {
        try {
            // 잠금 대기 시간 기본값(50초)보다 오래 잡고 있어야 설정 누락을 성공으로 오인하지 않는다.
            if (!release.await(70, TimeUnit.SECONDS)) {
                throw new IllegalStateException("잠금 해제 신호를 받지 못했습니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
