package com.loopers.application.order;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.point.PointApplicationService;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.domain.common.RuleViolationException;
import com.loopers.infrastructure.payment.TestPgServer;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderConcurrencyTest {
    private static final long TASK_TIMEOUT_SECONDS = 30;

    @Autowired
    private BrandApplicationService brandApplicationService;

    @Autowired
    private ProductApplicationService productApplicationService;

    @Autowired
    private PointApplicationService pointApplicationService;

    @Autowired
    private OrderApplicationService orderApplicationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private TestPgFixture testPgFixture;

    private TestPgServer server;

    private long brandId;

    @BeforeEach
    void prepare() {
        server = testPgFixture.start();
        brandId = brandApplicationService.create("브랜드").id().value();
    }

    @AfterEach
    void clean() throws InterruptedException {
        testPgFixture.close();
        jdbcTemplate.update("delete from order_items");
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("재고 5개 상품을 서로 다른 8개 주문이 동시에 1개씩 확정하면 5건만 결제 대기가 되고 3건은 재고 부족으로 거절되며 최종 재고 0·create 5회·잔액 변화 0이다")
    void confirmsOnlyAvailableStockUnderContention() throws Exception {
        long productId = productApplicationService.create(brandId, "한정 상품", 1000, 5).id();
        List<OrderResult> drafts = new ArrayList<>();
        for (long userId = 1; userId <= 8; userId++) {
            pointApplicationService.charge(userId, 10000);
            drafts.add(orderApplicationService.create(userId, List.of(new OrderApplicationService.ItemRequest(productId, 1))));
        }

        List<Outcome> outcomes = runConcurrently(drafts.stream()
            .map(draft -> (Callable<Void>) () -> {
                orderApplicationService.confirm(draft.userId(), draft.id(), TestPgFixture.command());
                return null;
            })
            .toList());

        Map<Result, Long> counts = count(outcomes);
        assertThat(counts.get(Result.SUCCESS)).isEqualTo(5);
        assertThat(counts.get(Result.BUSINESS_REJECTION)).isEqualTo(3);
        assertThat(counts.get(Result.TECHNICAL_ERROR)).as(technicalErrors(outcomes)).isZero();
        assertThat(outcomes).filteredOn(outcome -> outcome.result() == Result.BUSINESS_REJECTION)
            .extracting(Outcome::message)
            .containsOnly("재고가 부족합니다.");

        List<OrderResult> afterRace = drafts.stream()
            .map(draft -> orderApplicationService.getMyOrder(draft.userId(), draft.id()))
            .toList();
        List<OrderResult> pending = afterRace.stream().filter(order -> order.status().equals("PAYMENT_PENDING")).toList();
        int reservedQuantity = pending.stream().mapToInt(order -> order.items().get(0).quantity()).sum();
        long totalBalance = drafts.stream().mapToLong(draft -> pointApplicationService.balance(draft.userId())).sum();

        assertThat(pending).hasSize(5);
        assertThat(afterRace).filteredOn(order -> order.status().equals("DRAFT")).hasSize(3)
            .allSatisfy(order -> assertThat(order.paymentMethod()).isNull());
        assertThat(productApplicationService.getAdminProduct(productId).stock()).isEqualTo(5 - reservedQuantity).isZero();
        assertThat(server.createCalls()).isEqualTo(pending.size()).isEqualTo(5);
        assertThat(totalBalance).isEqualTo(8 * 10000);
    }

    @Test
    @DisplayName("같은 사용자가 서로 다른 상품의 4,000원 주문 3건을 동시에 확정하면 confirm이 포인트를 쓰지 않으므로 3건 모두 결제되고 잔액은 10,000원 그대로다")
    void confirmsSameUserOrdersWithoutTouchingBalance() throws Exception {
        server.createBehavior(TestPgServer.CreateBehavior.ACCEPT_SUCCESS);
        pointApplicationService.charge(1, 10000);
        List<OrderResult> drafts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long productId = productApplicationService.create(brandId, "4천원 상품 " + i, 4000, 10).id();
            drafts.add(orderApplicationService.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1))));
        }

        List<Outcome> outcomes = runConcurrently(drafts.stream()
            .map(draft -> (Callable<Void>) () -> {
                orderApplicationService.confirm(1, draft.id(), TestPgFixture.command());
                return null;
            })
            .toList());

        Map<Result, Long> counts = count(outcomes);
        assertThat(counts.get(Result.SUCCESS)).as(technicalErrors(outcomes)).isEqualTo(3);
        assertThat(counts.get(Result.BUSINESS_REJECTION)).isZero();
        assertThat(counts.get(Result.TECHNICAL_ERROR)).isZero();
        for (OrderResult draft : drafts) {
            OrderResult order = orderApplicationService.getMyOrder(1, draft.id());
            assertThat(order.status()).isEqualTo("CONFIRMED");
            assertThat(order.paymentMethod()).isEqualTo("PG");
            assertThat(order.paidAmount()).isEqualTo(4000);
            assertThat(productApplicationService.getAdminProduct(order.items().get(0).productId()).stock()).isEqualTo(9);
        }
        assertThat(server.createCalls()).isEqualTo(3);
        assertThat(pointApplicationService.balance(1)).isEqualTo(10000);
    }

    @Test
    @DisplayName("잔액 10,000원에서 2,000원 충전과 7,000원 주문 확정을 동시에 실행하면 둘 다 성공하고 확정이 포인트를 쓰지 않아 최종 잔액은 12,000원이다")
    void appliesConcurrentChargeAndConfirm() throws Exception {
        server.createBehavior(TestPgServer.CreateBehavior.ACCEPT_SUCCESS);
        long productId = productApplicationService.create(brandId, "7천원 상품", 7000, 10).id();
        pointApplicationService.charge(1, 10000);
        OrderResult draft = orderApplicationService.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1)));

        List<Outcome> outcomes = runConcurrently(List.of(
            () -> {
                pointApplicationService.charge(1, 2000);
                return null;
            },
            () -> {
                orderApplicationService.confirm(1, draft.id(), TestPgFixture.command());
                return null;
            }));

        Map<Result, Long> counts = count(outcomes);
        assertThat(counts.get(Result.SUCCESS)).as(technicalErrors(outcomes)).isEqualTo(2);
        assertThat(counts.get(Result.TECHNICAL_ERROR)).isZero();
        assertThat(pointApplicationService.balance(1)).isEqualTo(10000 + 2000);
        assertThat(orderApplicationService.getMyOrder(1, draft.id()).status()).isEqualTo("CONFIRMED");
        assertThat(productApplicationService.getAdminProduct(productId).stock()).isEqualTo(9);
        assertThat(server.createCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 주문을 같은 시도 키로 8번 동시에 확정하면 모두 지금 상태를 받고 create 1회·재고 확보 1회·PG 거래 1건이다")
    void confirmsOnceForConcurrentSameKey() throws Exception {
        long productId = productApplicationService.create(brandId, "상품", 1000, 5).id();
        OrderResult draft = orderApplicationService.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1)));
        String attemptKey = UUID.randomUUID().toString();

        List<Outcome> outcomes = runConcurrently(IntStream.range(0, 8)
            .mapToObj(i -> (Callable<Void>) () -> {
                orderApplicationService.confirm(1, draft.id(), TestPgFixture.command(attemptKey));
                return null;
            })
            .toList());

        Map<Result, Long> counts = count(outcomes);
        assertThat(counts.get(Result.SUCCESS)).as(technicalErrors(outcomes)).isEqualTo(8);
        assertThat(counts.get(Result.BUSINESS_REJECTION)).isZero();
        assertThat(counts.get(Result.TECHNICAL_ERROR)).isZero();
        assertSingleReservation(draft.id(), productId);
    }

    @Test
    @DisplayName("같은 주문을 서로 다른 시도 키로 8번 동시에 확정하면 1건만 결제를 시작하고 7건은 거절되며 create 1회·재고 확보 1회다")
    void confirmsOnceForConcurrentDifferentKeys() throws Exception {
        long productId = productApplicationService.create(brandId, "상품", 1000, 5).id();
        OrderResult draft = orderApplicationService.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1)));

        List<Outcome> outcomes = runConcurrently(IntStream.range(0, 8)
            .mapToObj(i -> (Callable<Void>) () -> {
                orderApplicationService.confirm(1, draft.id(), TestPgFixture.command());
                return null;
            })
            .toList());

        Map<Result, Long> counts = count(outcomes);
        assertThat(counts.get(Result.SUCCESS)).as(technicalErrors(outcomes)).isEqualTo(1);
        assertThat(counts.get(Result.BUSINESS_REJECTION)).isEqualTo(7);
        assertThat(counts.get(Result.TECHNICAL_ERROR)).isZero();
        assertThat(outcomes).filteredOn(outcome -> outcome.result() == Result.BUSINESS_REJECTION)
            .extracting(Outcome::message)
            .containsOnly("이미 다른 결제가 진행되었거나 끝난 주문입니다.");
        assertSingleReservation(draft.id(), productId);
    }

    private void assertSingleReservation(long orderId, long productId) {
        assertThat(orderApplicationService.getMyOrder(1, orderId).status()).isEqualTo("PAYMENT_PENDING");
        assertThat(productApplicationService.getAdminProduct(productId).stock()).isEqualTo(4);
        assertThat(server.createCalls()).isEqualTo(1);
        assertThat(server.transactions()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from payment_records where order_id = ?", Long.class, orderId))
            .isEqualTo(1);
    }

    // 시작 시점만 맞추고 실제 서비스 경로에는 장벽·sleep을 넣지 않는다. 모든 요청 결과를 성공·업무 거절·기술 오류로 수집한다.
    private List<Outcome> runConcurrently(List<Callable<Void>> tasks) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = tasks.stream()
                .map(task -> executor.submit(() -> {
                    if (!start.await(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        return new Outcome(Result.TECHNICAL_ERROR, "시작 신호 대기 시간 초과");
                    }
                    return execute(task);
                }))
                .toList();
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(await(future));
            }
            assertThat(outcomes).hasSize(tasks.size());
            return outcomes;
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private Outcome execute(Callable<Void> task) {
        try {
            task.call();
            return new Outcome(Result.SUCCESS, null);
        } catch (RuleViolationException e) {
            return new Outcome(Result.BUSINESS_REJECTION, e.getMessage());
        } catch (Exception e) {
            return new Outcome(Result.TECHNICAL_ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Outcome await(Future<Outcome> future) throws InterruptedException {
        try {
            return future.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new Outcome(Result.TECHNICAL_ERROR, "완료 대기 실패: " + e);
        }
    }

    private Map<Result, Long> count(List<Outcome> outcomes) {
        Map<Result, Long> counts = outcomes.stream()
            .collect(Collectors.groupingBy(Outcome::result, Collectors.counting()));
        for (Result result : Result.values()) {
            counts.putIfAbsent(result, 0L);
        }
        assertThat(counts.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(outcomes.size());
        return counts;
    }

    private String technicalErrors(List<Outcome> outcomes) {
        return outcomes.stream()
            .filter(outcome -> outcome.result() == Result.TECHNICAL_ERROR)
            .map(Outcome::message)
            .collect(Collectors.joining(", ", "기술 오류: [", "]"));
    }

    private enum Result {
        SUCCESS,
        BUSINESS_REJECTION,
        TECHNICAL_ERROR
    }

    private record Outcome(Result result, String message) { }
}
