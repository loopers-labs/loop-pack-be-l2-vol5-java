package com.loopers.application.order;

import com.loopers.application.point.PointFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.order.OrderItemCommand;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.point.PointChangeCause;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.StockChangeCause;
import com.loopers.domain.user.UserModel;
import com.loopers.fixture.OrderStateReader;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 실제 Facade 프록시 경쟁 테스트. fixture 는 worker 시작 전에 commit 하고, 테스트 전체를 부모 트랜잭션으로 감싸지 않는다.
 * worker 는 시작 신호만 함께 기다린 뒤 각자 Facade 를 호출해 독립 트랜잭션·connection 을 연다.
 * Facade 내부에는 장벽·sleep 을 넣지 않으며, 모든 worker 종료 후 새 조회로 최종 상태를 판정한다.
 * 각 요청의 최초 호출 결과만 집계하고 실패 요청을 다시 호출하지 않는다.
 */
@DisplayName("주문 확정 경쟁에서 요청별 결과와 최종 Order·Point·Product·History 가 일치한다.")
@SpringBootTest
class OrderConcurrencyIntegrationTest {

    private static final Duration STEP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration TERMINATION_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private OrderConfirmFacade orderConfirmFacade;
    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private PointFacade pointFacade;
    @Autowired
    private ProductFacade productFacade;
    @Autowired
    private ProductJpaRepository productJpaRepository;
    @Autowired
    private UserFixture userFixture;
    @Autowired
    private ProductFixture productFixture;
    @Autowired
    private OrderStateReader orderStateReader;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    /**
     * worker 종료를 실제로 확인한 경우에만 true 다. worker 실행 전과 종료 대기가 중단·초과된 경우는 미확인(false)으로 두어,
     * 살아 있는 트랜잭션과 겹치지 않도록 DB 정리를 하지 않는다.
     */
    private boolean workersTerminated;

    @AfterEach
    void tearDown() {
        if (workersTerminated) {
            databaseCleanUp.truncateAllTables();
        }
    }

    private enum Outcome { SUCCESS, BUSINESS_ERROR, TECHNICAL_ERROR }

    private record Request(String name, Callable<Object> call) {
    }

    private record RequestResult(String name, Outcome outcome, Object value, ErrorType errorType, Throwable error) {
        @Override
        public String toString() {
            return name + "=" + outcome + (errorType != null ? "(" + errorType + ")" : "")
                + (outcome == Outcome.TECHNICAL_ERROR ? "(" + error + ")" : "");
        }
    }

    /**
     * 요청 수만큼 worker 를 만들고 시작 신호만 맞춘다. 허용한 업무 오류 외의 예외는 기술 오류로 집계한다.
     * 준비·시작 대기 10초, 시작 해제 후 공통 마감 120초(commit 포함), 종료 확인 30초를 넘기면 테스트 실패다.
     */
    private List<RequestResult> runConcurrently(Set<ErrorType> allowedBusinessErrors, List<Request> requests)
        throws InterruptedException {
        int workers = requests.size();
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<RequestResult> results = new ArrayList<>();
        try {
            List<Future<RequestResult>> futures = new ArrayList<>();
            for (Request request : requests) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(STEP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException("시작 신호 대기 시간 초과");
                    }
                    try {
                        return new RequestResult(request.name(), Outcome.SUCCESS, request.call().call(), null, null);
                    } catch (CoreException e) {
                        Outcome outcome = allowedBusinessErrors.contains(e.getErrorType())
                            ? Outcome.BUSINESS_ERROR : Outcome.TECHNICAL_ERROR;
                        return new RequestResult(request.name(), outcome, null, e.getErrorType(), e);
                    } catch (Exception e) {
                        return new RequestResult(request.name(), Outcome.TECHNICAL_ERROR, null, null, e);
                    }
                }));
            }

            assertThat(ready.await(STEP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).as("worker 준비 시간 초과").isTrue();
            start.countDown();
            long deadline = System.nanoTime() + RUN_TIMEOUT.toNanos();
            for (Future<RequestResult> future : futures) {
                try {
                    results.add(future.get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                } catch (ExecutionException e) {
                    throw new AssertionError("worker 가 결과 없이 종료됐다", e.getCause());
                } catch (TimeoutException e) {
                    throw new AssertionError("시작 해제 후 공통 마감(" + RUN_TIMEOUT + ") 초과", e);
                }
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            workersTerminated = awaitWorkersTermination(executor);
        }
        assertThat(workersTerminated).as("worker 종료 미확인(시간 초과 또는 대기 중단): DB 정리를 하지 않았다").isTrue();
        return results;
    }

    private static long count(List<RequestResult> results, Outcome outcome) {
        return results.stream().filter(result -> result.outcome() == outcome).count();
    }

    private static List<List<Object>> rowsOf(List<List<Object>> historyRows, Object cause) {
        return historyRows.stream().filter(row -> row.get(5) == cause).toList();
    }

    /** History 행에서 id 를 뺀 [대상 ID, 변경 전, 변경 후, 변경량, 원인, 주문 ID]. */
    private static List<List<Object>> withoutId(List<List<Object>> historyRows) {
        return historyRows.stream().map(row -> row.subList(1, row.size())).toList();
    }

    @DisplayName("같은 주문")
    @Nested
    class SameOrder {
        @DisplayName("같은 DRAFT 를 4개 요청이 동시에 확정하면 1건만 성공하고 나머지는 ORDER_NOT_CONFIRMABLE, 차감·History 는 한 번만 남는다.")
        @Test
        void confirmsOnlyOnce() throws Exception {
            UserModel user = userFixture.createUserWithPoint();
            pointFacade.charge(user.getId(), 50_000L);
            ProductModel shirt = productFixture.createProduct("티셔츠", 2_000L, 5L);
            ProductModel pants = productFixture.createProduct("바지", 3_000L, 4L);
            OrderModel order = orderFacade.create(user.getId(), List.of(
                new OrderItemCommand(shirt.getId(), 2L),
                new OrderItemCommand(pants.getId(), 1L)
            ));
            List<List<Object>> pointHistoriesBefore = orderStateReader.pointHistoryRows();

            List<Request> requests = new ArrayList<>();
            for (int i = 1; i <= 4; i++) {
                requests.add(new Request("confirm-" + i, () -> orderConfirmFacade.confirm(user.getId(), order.getId())));
            }
            List<RequestResult> results = runConcurrently(Set.of(ErrorType.ORDER_NOT_CONFIRMABLE), requests);
            System.out.println("[O-T4] results = " + results);

            var state = orderStateReader.confirmState(order.getId(), user.getId(), List.of(shirt.getId(), pants.getId()));
            Long pointId = (Long) pointHistoriesBefore.get(0).get(1);
            assertAll(
                () -> assertThat(count(results, Outcome.TECHNICAL_ERROR)).as("기술 오류 " + results).isZero(),
                () -> assertThat(count(results, Outcome.SUCCESS)).as("성공").isEqualTo(1),
                () -> assertThat(count(results, Outcome.BUSINESS_ERROR)).as("ORDER_NOT_CONFIRMABLE").isEqualTo(3),
                () -> assertThat(results).filteredOn(result -> result.outcome() == Outcome.SUCCESS)
                    .extracting(result -> ((OrderInfo) result.value()).status()).containsExactly("CONFIRMED"),
                () -> assertThat(state.order()).isEqualTo(Arrays.asList(user.getId(), OrderStatus.CONFIRMED,
                    7_000L, 7_000L, 7_000L, List.of(
                        List.of(shirt.getId(), 2L, 2_000L), List.of(pants.getId(), 1L, 3_000L)))),
                () -> assertThat(state.balance()).as("50,000 − 성공 1건 7,000").isEqualTo(43_000L),
                () -> assertThat(state.stocks()).as("품목 수량을 한 번만 차감").containsExactly(3L, 3L),
                () -> assertThat(state.pointHistories().subList(0, pointHistoriesBefore.size()))
                    .as("준비 History 기준선 유지").isEqualTo(pointHistoriesBefore),
                () -> assertThat(withoutId(rowsOf(state.pointHistories(), PointChangeCause.ORDER_USE)))
                    .containsExactly(Arrays.asList(pointId, 50_000L, 43_000L, 7_000L, PointChangeCause.ORDER_USE,
                        order.getId())),
                () -> assertThat(withoutId(rowsOf(state.stockHistories(), StockChangeCause.ORDER_DEDUCTION)))
                    .containsExactlyInAnyOrder(
                        Arrays.asList(shirt.getId(), 5L, 3L, 2L, StockChangeCause.ORDER_DEDUCTION, order.getId()),
                        Arrays.asList(pants.getId(), 4L, 3L, 1L, StockChangeCause.ORDER_DEDUCTION, order.getId())),
                () -> assertThat(state.stockHistories()).hasSize(2)
            );
        }
    }

    @DisplayName("같은 사용자 Point")
    @Nested
    class PointContention {
        @DisplayName("잔액 10,000 인 사용자의 4,000원 DRAFT 3개를 동시에 확정하면 2건 성공·1건 INSUFFICIENT_POINT, 잔액 2,000 이고 거절 주문의 재고는 유지된다.")
        @Test
        void confirmsOnlyWhatBalanceAllows() throws Exception {
            UserModel user = userFixture.createUserWithPoint();
            pointFacade.charge(user.getId(), 10_000L);
            List<ProductModel> products = new ArrayList<>();
            List<OrderModel> orders = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                ProductModel product = productFixture.createProduct("상품" + i, 4_000L, 5L);
                products.add(product);
                orders.add(orderFacade.create(user.getId(), List.of(new OrderItemCommand(product.getId(), 1L))));
            }
            List<List<Object>> pointHistoriesBefore = orderStateReader.pointHistoryRows();
            Long pointId = (Long) pointHistoriesBefore.get(0).get(1);

            List<Request> requests = orders.stream()
                .map(order -> new Request("confirm-order" + order.getId(),
                    () -> orderConfirmFacade.confirm(user.getId(), order.getId())))
                .toList();
            List<RequestResult> results = runConcurrently(Set.of(ErrorType.INSUFFICIENT_POINT), requests);
            System.out.println("[O-T5] results = " + results);

            List<Long> confirmedOrderIds = new ArrayList<>();
            List<Executable> perOrder = new ArrayList<>();
            for (int i = 0; i < orders.size(); i++) {
                OrderModel order = orders.get(i);
                ProductModel product = products.get(i);
                boolean confirmed = results.get(i).outcome() == Outcome.SUCCESS;
                if (confirmed) {
                    confirmedOrderIds.add(order.getId());
                }
                List<Object> expectedOrder = confirmed
                    ? Arrays.asList(user.getId(), OrderStatus.CONFIRMED, 4_000L, 4_000L, 4_000L,
                        List.of(List.of(product.getId(), 1L, 4_000L)))
                    : Arrays.asList(user.getId(), OrderStatus.DRAFT, 4_000L, null, null,
                        List.of(List.of(product.getId(), 1L, 4_000L)));
                perOrder.add(() -> assertThat(orderStateReader.orderRow(order.getId())).isEqualTo(expectedOrder));
                perOrder.add(() -> assertThat(orderStateReader.stocks(List.of(product.getId())))
                    .as("주문 " + order.getId() + " 상품 재고").containsExactly(confirmed ? 4L : 5L));
            }
            List<List<Object>> pointHistories = orderStateReader.pointHistoryRows();
            List<List<Object>> orderUses = withoutId(rowsOf(pointHistories, PointChangeCause.ORDER_USE));
            List<List<Object>> deductions = withoutId(rowsOf(orderStateReader.stockHistoryRows(),
                StockChangeCause.ORDER_DEDUCTION));

            assertAll(
                () -> assertThat(count(results, Outcome.TECHNICAL_ERROR)).as("기술 오류 " + results).isZero(),
                () -> assertThat(count(results, Outcome.SUCCESS)).as("성공").isEqualTo(2),
                () -> assertThat(count(results, Outcome.BUSINESS_ERROR)).as("INSUFFICIENT_POINT").isEqualTo(1),
                () -> assertAll(perOrder),
                () -> assertThat(orderStateReader.balance(user.getId())).as("10,000 − 성공 2건 × 4,000")
                    .isEqualTo(2_000L),
                () -> assertThat(pointHistories.subList(0, pointHistoriesBefore.size()))
                    .as("준비 History 기준선 유지").isEqualTo(pointHistoriesBefore),
                () -> assertThat(orderUses).as("성공 주문의 Point 사용 이력이 10,000 → 6,000 → 2,000 으로 이어진다")
                    .extracting(row -> List.of(row.get(0), row.get(1), row.get(2), row.get(3)))
                    .containsExactly(List.of(pointId, 10_000L, 6_000L, 4_000L), List.of(pointId, 6_000L, 2_000L, 4_000L)),
                () -> assertThat(orderUses).extracting(row -> row.get(5))
                    .containsExactlyInAnyOrderElementsOf(confirmedOrderIds),
                () -> assertThat(deductions).extracting(row -> row.get(5))
                    .containsExactlyInAnyOrderElementsOf(confirmedOrderIds),
                () -> assertThat(deductions).extracting(row -> List.of(row.get(1), row.get(2), row.get(3)))
                    .containsOnly(List.of(5L, 4L, 1L))
            );
        }

        @DisplayName("잔액 10,000 에서 2,000 충전과 7,000 주문 확정이 동시에 실행되면 둘 다 성공하고 잔액은 5,000, 이력은 가능한 직렬 순서 중 하나로 이어진다.")
        @Test
        void keepsBothChargeAndPayment() throws Exception {
            UserModel user = userFixture.createUserWithPoint();
            pointFacade.charge(user.getId(), 10_000L);
            ProductModel product = productFixture.createProduct("가방", 7_000L, 5L);
            OrderModel order = orderFacade.create(user.getId(), List.of(new OrderItemCommand(product.getId(), 1L)));
            List<List<Object>> pointHistoriesBefore = orderStateReader.pointHistoryRows();
            Long pointId = (Long) pointHistoriesBefore.get(0).get(1);

            List<RequestResult> results = runConcurrently(Set.of(), List.of(
                new Request("charge-2000", () -> pointFacade.charge(user.getId(), 2_000L)),
                new Request("confirm", () -> orderConfirmFacade.confirm(user.getId(), order.getId()))
            ));
            System.out.println("[O-T6] results = " + results);

            List<List<Object>> pointHistories = orderStateReader.pointHistoryRows();
            List<List<Object>> executed = withoutId(pointHistories.subList(pointHistoriesBefore.size(),
                pointHistories.size()));
            List<List<Object>> chargeFirst = List.of(
                Arrays.asList(pointId, 10_000L, 12_000L, 2_000L, PointChangeCause.CHARGE, null),
                Arrays.asList(pointId, 12_000L, 5_000L, 7_000L, PointChangeCause.ORDER_USE, order.getId()));
            List<List<Object>> paymentFirst = List.of(
                Arrays.asList(pointId, 10_000L, 3_000L, 7_000L, PointChangeCause.ORDER_USE, order.getId()),
                Arrays.asList(pointId, 3_000L, 5_000L, 2_000L, PointChangeCause.CHARGE, null));
            System.out.println("[O-T6] executed point histories = " + executed);

            assertAll(
                () -> assertThat(count(results, Outcome.TECHNICAL_ERROR)).as("기술 오류 " + results).isZero(),
                () -> assertThat(count(results, Outcome.SUCCESS)).as("성공").isEqualTo(2),
                () -> assertThat(orderStateReader.balance(user.getId())).as("10,000 + 2,000 − 7,000")
                    .isEqualTo(5_000L),
                () -> assertThat(pointHistories.subList(0, pointHistoriesBefore.size()))
                    .as("준비 History 기준선 유지").isEqualTo(pointHistoriesBefore),
                () -> assertThat(executed).as("실행분 이력이 초기 10,000 에서 최종 5,000 으로 이어진다")
                    .isIn(chargeFirst, paymentFirst),
                () -> assertThat(orderStateReader.orderRow(order.getId())).isEqualTo(Arrays.asList(user.getId(),
                    OrderStatus.CONFIRMED, 7_000L, 7_000L, 7_000L, List.of(List.of(product.getId(), 1L, 7_000L)))),
                () -> assertThat(withoutId(rowsOf(orderStateReader.stockHistoryRows(), StockChangeCause.ORDER_DEDUCTION)))
                    .containsExactly(Arrays.asList(product.getId(), 5L, 4L, 1L, StockChangeCause.ORDER_DEDUCTION,
                        order.getId())),
                () -> assertThat(orderStateReader.stocks(List.of(product.getId()))).containsExactly(4L)
            );
        }
    }

    @DisplayName("같은 상품 재고")
    @Nested
    class ProductContention {

        private UserModel userWithPoint(long amount) {
            UserModel user = userFixture.createUserWithPoint();
            pointFacade.charge(user.getId(), amount);
            return user;
        }

        /** 상품의 실행분 재고 이력이 초기 수량에서 최종 수량까지 before/after 로 끊김 없이 이어지는지 확인한다. */
        private void assertChained(List<List<Object>> productRows, long initial, long last) {
            long current = initial;
            for (List<Object> row : productRows) {
                assertThat(row.get(2)).as("이력 before " + productRows).isEqualTo(current);
                current = (Long) row.get(3);
            }
            assertThat(current).as("이력 마지막 after " + productRows).isEqualTo(last);
        }

        private List<List<Object>> stockRowsOf(Long productId) {
            return orderStateReader.stockHistoryRows().stream()
                .filter(row -> productId.equals(row.get(1))).toList();
        }

        @DisplayName("재고 5 인 상품을 서로 다른 사용자 8명이 1개씩 동시에 확정하면 5건 성공·3건 INSUFFICIENT_STOCK, 최종 재고 0 이다.")
        @Test
        void confirmsOnlyAvailableStock() throws Exception {
            ProductModel product = productFixture.createProduct("한정판 운동화", 1_000L, 5L);
            List<UserModel> users = new ArrayList<>();
            List<OrderModel> orders = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                UserModel user = userWithPoint(10_000L);
                users.add(user);
                orders.add(orderFacade.create(user.getId(), List.of(new OrderItemCommand(product.getId(), 1L))));
            }

            List<Request> requests = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                UserModel user = users.get(i);
                OrderModel order = orders.get(i);
                requests.add(new Request("confirm-order" + order.getId(),
                    () -> orderConfirmFacade.confirm(user.getId(), order.getId())));
            }
            List<RequestResult> results = runConcurrently(Set.of(ErrorType.INSUFFICIENT_STOCK), requests);
            System.out.println("[O-T7] results = " + results);

            List<Long> confirmedOrderIds = new ArrayList<>();
            List<Executable> perOrder = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                UserModel user = users.get(i);
                OrderModel order = orders.get(i);
                boolean confirmed = results.get(i).outcome() == Outcome.SUCCESS;
                if (confirmed) {
                    confirmedOrderIds.add(order.getId());
                }
                List<Object> expectedOrder = confirmed
                    ? Arrays.asList(user.getId(), OrderStatus.CONFIRMED, 1_000L, 1_000L, 1_000L,
                        List.of(List.of(product.getId(), 1L, 1_000L)))
                    : Arrays.asList(user.getId(), OrderStatus.DRAFT, 1_000L, null, null,
                        List.of(List.of(product.getId(), 1L, 1_000L)));
                perOrder.add(() -> assertThat(orderStateReader.orderRow(order.getId())).isEqualTo(expectedOrder));
                perOrder.add(() -> assertThat(orderStateReader.balance(user.getId()))
                    .as("주문 " + order.getId() + " 사용자 잔액").isEqualTo(confirmed ? 9_000L : 10_000L));
            }
            List<List<Object>> deductions = rowsOf(stockRowsOf(product.getId()), StockChangeCause.ORDER_DEDUCTION);

            assertAll(
                () -> assertThat(count(results, Outcome.TECHNICAL_ERROR)).as("기술 오류 " + results).isZero(),
                () -> assertThat(count(results, Outcome.SUCCESS)).as("성공").isEqualTo(5),
                () -> assertThat(count(results, Outcome.BUSINESS_ERROR)).as("INSUFFICIENT_STOCK").isEqualTo(3),
                () -> assertAll(perOrder),
                () -> assertThat(orderStateReader.stocks(List.of(product.getId()))).as("5 − 성공 5건 × 1")
                    .containsExactly(0L),
                () -> assertThat(deductions).extracting(row -> row.get(6))
                    .containsExactlyInAnyOrderElementsOf(confirmedOrderIds),
                () -> assertChained(deductions, 5L, 0L),
                () -> assertThat(withoutId(rowsOf(orderStateReader.pointHistoryRows(), PointChangeCause.ORDER_USE)))
                    .extracting(row -> row.get(5)).containsExactlyInAnyOrderElementsOf(confirmedOrderIds)
            );
        }

        @DisplayName("재고 5 를 최종 10 으로 설정하는 관리자 변경과 수량 1 주문 확정이 동시에 실행되면 둘 다 성공하고, 최종 재고와 이력은 가능한 직렬 순서 중 하나다.")
        @Test
        void keepsAdminChangeAndDeduction() throws Exception {
            ProductModel product = productFixture.createProduct("티셔츠", 1_000L, 5L);
            UserModel user = userWithPoint(10_000L);
            OrderModel order = orderFacade.create(user.getId(), List.of(new OrderItemCommand(product.getId(), 1L)));

            List<RequestResult> results = runConcurrently(Set.of(), List.of(
                new Request("admin-set-10", () -> productFacade.changeStock(product.getId(), 10L)),
                new Request("confirm", () -> orderConfirmFacade.confirm(user.getId(), order.getId()))
            ));
            System.out.println("[O-T8] results = " + results);

            List<List<Object>> executed = withoutId(stockRowsOf(product.getId()));
            System.out.println("[O-T8] executed stock histories = " + executed);
            List<List<Object>> adminFirst = List.of(
                Arrays.asList(product.getId(), 5L, 10L, 5L, StockChangeCause.ADMIN_CHANGE, null),
                Arrays.asList(product.getId(), 10L, 9L, 1L, StockChangeCause.ORDER_DEDUCTION, order.getId()));
            List<List<Object>> deductionFirst = List.of(
                Arrays.asList(product.getId(), 5L, 4L, 1L, StockChangeCause.ORDER_DEDUCTION, order.getId()),
                Arrays.asList(product.getId(), 4L, 10L, 6L, StockChangeCause.ADMIN_CHANGE, null));
            long finalStock = orderStateReader.stocks(List.of(product.getId())).get(0);

            assertAll(
                () -> assertThat(count(results, Outcome.TECHNICAL_ERROR)).as("기술 오류 " + results).isZero(),
                () -> assertThat(count(results, Outcome.SUCCESS)).as("성공").isEqualTo(2),
                () -> assertThat(executed).isIn(adminFirst, deductionFirst),
                () -> assertThat(finalStock).as("이력의 마지막 after 와 같은 최종 재고")
                    .isEqualTo(executed.equals(adminFirst) ? 9L : 10L),
                () -> assertThat(orderStateReader.orderRow(order.getId())).isEqualTo(Arrays.asList(user.getId(),
                    OrderStatus.CONFIRMED, 1_000L, 1_000L, 1_000L, List.of(List.of(product.getId(), 1L, 1_000L)))),
                () -> assertThat(orderStateReader.balance(user.getId())).isEqualTo(9_000L),
                () -> assertThat(withoutId(rowsOf(orderStateReader.pointHistoryRows(), PointChangeCause.ORDER_USE)))
                    .containsExactly(Arrays.asList(orderStateReader.pointId(user.getId()), 10_000L, 9_000L, 1_000L,
                        PointChangeCause.ORDER_USE, order.getId()))
            );
        }

        @DisplayName("두 상품을 함께 담은 주문 8개(입력 순서 절반은 반대)를 동시에 확정하면 모두 성공하고 각 상품 재고 8 이 0 이 된다.")
        @Test
        void confirmsOverlappingProductsWithoutDeadlock() throws Exception {
            ProductModel first = productFixture.createProduct("상품 A", 1_000L, 8L);
            ProductModel second = productFixture.createProduct("상품 B", 2_000L, 8L);
            List<UserModel> users = new ArrayList<>();
            List<OrderModel> orders = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                UserModel user = userWithPoint(10_000L);
                users.add(user);
                List<OrderItemCommand> items = i % 2 == 0
                    ? List.of(new OrderItemCommand(first.getId(), 1L), new OrderItemCommand(second.getId(), 1L))
                    : List.of(new OrderItemCommand(second.getId(), 1L), new OrderItemCommand(first.getId(), 1L));
                orders.add(orderFacade.create(user.getId(), items));
            }

            List<Request> requests = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                UserModel user = users.get(i);
                OrderModel order = orders.get(i);
                requests.add(new Request("confirm-order" + order.getId(),
                    () -> orderConfirmFacade.confirm(user.getId(), order.getId())));
            }
            List<RequestResult> results = runConcurrently(Set.of(), requests);
            System.out.println("[O-T9] results = " + results);

            List<Long> orderIds = orders.stream().map(OrderModel::getId).toList();
            List<Executable> perOrder = new ArrayList<>();
            List<List<Object>> expectedOrderUses = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                UserModel user = users.get(i);
                OrderModel order = orders.get(i);
                perOrder.add(() -> assertThat(orderStateReader.orderRow(order.getId())).isEqualTo(Arrays.asList(
                    user.getId(), OrderStatus.CONFIRMED, 3_000L, 3_000L, 3_000L, List.of(
                        List.of(first.getId(), 1L, 1_000L), List.of(second.getId(), 1L, 2_000L)))));
                expectedOrderUses.add(Arrays.asList(orderStateReader.pointId(user.getId()), 10_000L, 7_000L, 3_000L,
                    PointChangeCause.ORDER_USE, order.getId()));
            }
            List<List<Object>> firstRows = rowsOf(stockRowsOf(first.getId()), StockChangeCause.ORDER_DEDUCTION);
            List<List<Object>> secondRows = rowsOf(stockRowsOf(second.getId()), StockChangeCause.ORDER_DEDUCTION);

            assertAll(
                () -> assertThat(count(results, Outcome.TECHNICAL_ERROR)).as("기술 오류 " + results).isZero(),
                () -> assertThat(count(results, Outcome.SUCCESS)).as("성공").isEqualTo(8),
                () -> assertThat(orderStateReader.stocks(List.of(first.getId(), second.getId())))
                    .containsExactly(0L, 0L),
                () -> assertThat(firstRows).extracting(row -> row.get(6)).containsExactlyInAnyOrderElementsOf(orderIds),
                () -> assertThat(secondRows).extracting(row -> row.get(6)).containsExactlyInAnyOrderElementsOf(orderIds),
                () -> assertChained(firstRows, 8L, 0L),
                () -> assertChained(secondRows, 8L, 0L),
                () -> assertAll(perOrder),
                () -> assertThat(users).allSatisfy(user -> assertThat(orderStateReader.balance(user.getId()))
                    .isEqualTo(7_000L)),
                () -> assertThat(withoutId(rowsOf(orderStateReader.pointHistoryRows(), PointChangeCause.ORDER_USE)))
                    .as("성공 주문별 Point 사용 이력 1건").containsExactlyInAnyOrderElementsOf(expectedOrderUses)
            );
        }

        @DisplayName("상품 정보 수정과 주문 확정이 동시에 실행되면 둘 다 성공하고, 재고 차감·수정 정보·주문 저장 가격이 모두 유지된다.")
        @Test
        void keepsProductUpdateAndDeduction() throws Exception {
            ProductModel product = productFixture.createProduct("티셔츠", 2_000L, 5L);
            UserModel user = userWithPoint(10_000L);
            OrderModel order = orderFacade.create(user.getId(), List.of(new OrderItemCommand(product.getId(), 1L)));

            List<RequestResult> results = runConcurrently(Set.of(), List.of(
                new Request("update", () -> productFacade.update(product.getId(), "새 티셔츠", 3_000L)),
                new Request("confirm", () -> orderConfirmFacade.confirm(user.getId(), order.getId()))
            ));
            System.out.println("[O-T10] results = " + results);

            ProductModel stored = productJpaRepository.findById(product.getId()).orElseThrow();
            assertAll(
                () -> assertThat(count(results, Outcome.TECHNICAL_ERROR)).as("기술 오류 " + results).isZero(),
                () -> assertThat(count(results, Outcome.SUCCESS)).as("성공").isEqualTo(2),
                () -> assertThat(stored.getStockQuantity()).as("5 − 1").isEqualTo(4L),
                () -> assertThat(stored.getName()).isEqualTo("새 티셔츠"),
                () -> assertThat(stored.getPrice().toWon()).isEqualTo(3_000L),
                () -> assertThat(orderStateReader.orderRow(order.getId())).isEqualTo(Arrays.asList(user.getId(),
                    OrderStatus.CONFIRMED, 2_000L, 2_000L, 2_000L, List.of(List.of(product.getId(), 1L, 2_000L)))),
                () -> assertThat(orderStateReader.balance(user.getId())).isEqualTo(8_000L),
                () -> assertThat(withoutId(stockRowsOf(product.getId()))).containsExactly(
                    Arrays.asList(product.getId(), 5L, 4L, 1L, StockChangeCause.ORDER_DEDUCTION, order.getId())),
                () -> assertThat(withoutId(rowsOf(orderStateReader.pointHistoryRows(), PointChangeCause.ORDER_USE)))
                    .extracting(row -> row.get(5)).containsExactly(order.getId())
            );
        }

        @DisplayName("상품 삭제와 주문 확정이 동시에 실행되면 확정 먼저면 둘 다 성공·재고 4, 삭제 먼저면 PRODUCT_NOT_FOUND·재고 5·DRAFT 다.")
        @Test
        void keepsProductDeleteAndConfirmConsistent() throws Exception {
            ProductModel product = productFixture.createProduct("티셔츠", 2_000L, 5L);
            UserModel user = userWithPoint(10_000L);
            OrderModel order = orderFacade.create(user.getId(), List.of(new OrderItemCommand(product.getId(), 1L)));
            List<List<Object>> pointHistoriesBefore = orderStateReader.pointHistoryRows();

            List<RequestResult> results = runConcurrently(Set.of(ErrorType.PRODUCT_NOT_FOUND), List.of(
                new Request("delete", () -> {
                    productFacade.delete(product.getId());
                    return null;
                }),
                new Request("confirm", () -> orderConfirmFacade.confirm(user.getId(), order.getId()))
            ));
            System.out.println("[O-T11] results = " + results);

            RequestResult delete = results.get(0);
            RequestResult confirm = results.get(1);
            ProductModel stored = productJpaRepository.findById(product.getId()).orElseThrow();
            List<Object> items = List.of(List.of(product.getId(), 1L, 2_000L));
            boolean confirmedFirst = confirm.outcome() == Outcome.SUCCESS;
            assertAll(
                () -> assertThat(count(results, Outcome.TECHNICAL_ERROR)).as("기술 오류 " + results).isZero(),
                () -> assertThat(delete.outcome()).as("삭제").isEqualTo(Outcome.SUCCESS),
                () -> assertThat(stored.getDeletedAt()).isNotNull(),
                () -> {
                    if (confirmedFirst) {
                        assertAll(
                            () -> assertThat(stored.getStockQuantity()).isEqualTo(4L),
                            () -> assertThat(orderStateReader.orderRow(order.getId())).isEqualTo(Arrays.asList(
                                user.getId(), OrderStatus.CONFIRMED, 2_000L, 2_000L, 2_000L, items)),
                            () -> assertThat(orderStateReader.balance(user.getId())).isEqualTo(8_000L),
                            () -> assertThat(withoutId(stockRowsOf(product.getId()))).containsExactly(Arrays.asList(
                                product.getId(), 5L, 4L, 1L, StockChangeCause.ORDER_DEDUCTION, order.getId())),
                            () -> assertThat(withoutId(rowsOf(orderStateReader.pointHistoryRows(),
                                PointChangeCause.ORDER_USE))).containsExactly(Arrays.asList(
                                    orderStateReader.pointId(user.getId()), 10_000L, 8_000L, 2_000L,
                                    PointChangeCause.ORDER_USE, order.getId()))
                        );
                    } else {
                        assertAll(
                            () -> assertThat(confirm.errorType()).isEqualTo(ErrorType.PRODUCT_NOT_FOUND),
                            () -> assertThat(stored.getStockQuantity()).isEqualTo(5L),
                            () -> assertThat(orderStateReader.orderRow(order.getId())).isEqualTo(Arrays.asList(
                                user.getId(), OrderStatus.DRAFT, 2_000L, null, null, items)),
                            () -> assertThat(orderStateReader.balance(user.getId())).isEqualTo(10_000L),
                            () -> assertThat(stockRowsOf(product.getId())).isEmpty(),
                            () -> assertThat(orderStateReader.pointHistoryRows()).isEqualTo(pointHistoriesBefore)
                        );
                    }
                }
            );
        }
    }

    /** 종료를 확인한 경우에만 true 를 돌려준다. 대기가 중단되면 인터럽트 상태를 복원하고 미확인(false)으로 둔다. */
    private static boolean awaitWorkersTermination(ExecutorService executor) {
        try {
            return executor.awaitTermination(TERMINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
