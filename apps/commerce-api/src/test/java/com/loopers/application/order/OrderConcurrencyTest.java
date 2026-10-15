package com.loopers.application.order;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.point.PointApplicationService;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.domain.common.RuleViolationException;
import com.loopers.utils.DatabaseCleanUp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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

    private long brandId;

    @BeforeEach
    void prepare() {
        brandId = brandApplicationService.create("브랜드").id().value();
    }

    @AfterEach
    void clean() {
        jdbcTemplate.update("delete from order_items");
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("재고 5개 상품을 서로 다른 8개 주문이 동시에 1개씩 확정하면 5건만 성공하고 3건은 재고 부족으로 거절되며 최종 재고는 0이다")
    void confirmsOnlyAvailableStockUnderContention() throws Exception {
        long productId = productApplicationService.create(brandId, "한정 상품", 1000, 5).id();
        List<OrderResult> drafts = new ArrayList<>();
        for (long userId = 1; userId <= 8; userId++) {
            pointApplicationService.charge(userId, 10000);
            drafts.add(orderApplicationService.create(userId, List.of(new OrderApplicationService.ItemRequest(productId, 1))));
        }

        List<Outcome> outcomes = runConcurrently(drafts.stream()
            .map(draft -> (Callable<Void>) () -> {
                orderApplicationService.confirm(draft.userId(), draft.id());
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
        List<OrderResult> confirmed = afterRace.stream().filter(order -> order.status().equals("CONFIRMED")).toList();
        int soldQuantity = confirmed.stream().mapToInt(order -> order.items().get(0).quantity()).sum();
        long paidTotal = confirmed.stream().mapToLong(OrderResult::paidAmount).sum();
        long totalBalance = drafts.stream().mapToLong(draft -> pointApplicationService.balance(draft.userId())).sum();

        assertThat(confirmed).hasSize(5);
        assertThat(afterRace).filteredOn(order -> order.status().equals("DRAFT"))
            .allSatisfy(order -> assertThat(order.paidAmount()).isZero());
        assertThat(productApplicationService.getAdminProduct(productId).stock()).isEqualTo(5 - soldQuantity).isZero();
        assertThat(totalBalance).isEqualTo(8 * 10000 - paidTotal).isEqualTo(75000);
    }

    @Test
    @DisplayName("잔액 10,000원 사용자가 서로 다른 상품의 4,000원 주문 3건을 동시에 확정하면 2건만 결제되고 거절된 주문과 재고는 유지된다")
    void protectsSameUserBalanceUnderContention() throws Exception {
        pointApplicationService.charge(1, 10000);
        List<OrderResult> drafts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            // 상품 잠금에서 먼저 직렬화되지 않도록 주문마다 다른 상품을 사용해 같은 포인트 행에서 경쟁하게 한다.
            long productId = productApplicationService.create(brandId, "4천원 상품 " + i, 4000, 10).id();
            drafts.add(orderApplicationService.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1))));
        }

        List<Outcome> outcomes = runConcurrently(drafts.stream()
            .map(draft -> (Callable<Void>) () -> {
                orderApplicationService.confirm(1, draft.id());
                return null;
            })
            .toList());

        Map<Result, Long> counts = count(outcomes);
        assertThat(counts.get(Result.SUCCESS)).isEqualTo(2);
        assertThat(counts.get(Result.BUSINESS_REJECTION)).isEqualTo(1);
        assertThat(counts.get(Result.TECHNICAL_ERROR)).as(technicalErrors(outcomes)).isZero();
        assertThat(outcomes).filteredOn(outcome -> outcome.result() == Result.BUSINESS_REJECTION)
            .extracting(Outcome::message)
            .containsOnly("잔액이 부족합니다.");

        List<OrderResult> afterRace = drafts.stream()
            .map(draft -> orderApplicationService.getMyOrder(1, draft.id()))
            .toList();
        // outcomes는 제출 순서를 유지하므로 요청 결과를 같은 순서의 주문·상품 DB 상태와 대조한다.
        for (int i = 0; i < afterRace.size(); i++) {
            OrderResult order = afterRace.get(i);
            OrderResult.Item item = order.items().get(0);
            if (outcomes.get(i).result() == Result.SUCCESS) {
                assertThat(order.status()).isEqualTo("CONFIRMED");
                assertThat(order.paymentResult()).isEqualTo("SUCCESS");
                assertThat(order.paidAmount()).isEqualTo(4000);
                assertThat(productApplicationService.getAdminProduct(item.productId()).stock())
                    .isEqualTo(10 - item.quantity()).isEqualTo(9);
            } else {
                assertThat(order).isEqualTo(drafts.get(i));
                assertThat(order.status()).isEqualTo("DRAFT");
                assertThat(order.paymentResult()).isEqualTo("NOT_PAID");
                assertThat(order.paidAmount()).isZero();
                assertThat(productApplicationService.getAdminProduct(item.productId()).stock()).isEqualTo(10);
            }
        }
        assertThat(afterRace).filteredOn(order -> order.status().equals("CONFIRMED")).hasSize(2);
        long paidTotal = afterRace.stream().mapToLong(OrderResult::paidAmount).sum();
        assertThat(pointApplicationService.balance(1)).isEqualTo(10000 - paidTotal).isEqualTo(2000);
    }

    @Test
    @DisplayName("잔액 10,000원에서 2,000원 충전과 7,000원 주문 확정을 동시에 실행하면 둘 다 성공하고 최종 잔액은 5,000원이다")
    void appliesConcurrentChargeAndPayment() throws Exception {
        long productId = productApplicationService.create(brandId, "7천원 상품", 7000, 10).id();
        pointApplicationService.charge(1, 10000);
        OrderResult draft = orderApplicationService.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1)));

        List<Outcome> outcomes = runConcurrently(List.of(
            () -> {
                pointApplicationService.charge(1, 2000);
                return null;
            },
            () -> {
                orderApplicationService.confirm(1, draft.id());
                return null;
            }));

        Map<Result, Long> counts = count(outcomes);
        assertThat(counts.get(Result.SUCCESS)).as(technicalErrors(outcomes)).isEqualTo(2);
        assertThat(counts.get(Result.TECHNICAL_ERROR)).isZero();
        assertThat(pointApplicationService.balance(1)).isEqualTo(10000 + 2000 - 7000);
        assertThat(orderApplicationService.getMyOrder(1, draft.id()).status()).isEqualTo("CONFIRMED");
        assertThat(productApplicationService.getAdminProduct(productId).stock()).isEqualTo(9);
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
