package com.loopers.application.order;

import com.loopers.fixture.PgTestSupport;
import com.loopers.fixture.PgCommands;
import com.loopers.application.point.PointFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.order.OrderItemCommand;
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
import org.junit.jupiter.api.Test;
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

/**
 * 실제 Facade 프록시 경쟁 테스트. fixture 는 worker 시작 전에 commit 하고, 테스트 전체를 부모 트랜잭션으로 감싸지 않는다.
 * worker 는 시작 신호만 함께 기다린 뒤 각자 Facade 를 호출해 독립 트랜잭션·connection 을 연다.
 * Facade 내부에는 장벽·sleep 을 넣지 않으며, 모든 worker 종료 후 새 조회로 최종 상태를 판정한다.
 * 각 요청의 최초 호출 결과만 집계하고 실패 요청을 다시 호출하지 않는다.
 */
@DisplayName("주문 확정 경쟁에서 요청별 결과와 최종 Order·Point·Product·History 가 일치한다.")
@SpringBootTest
class OrderConcurrencyIntegrationTest extends PgTestSupport {

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

    private UserModel customer() {
        UserModel user = userFixture.createUserWithPoint();
        pointFacade.charge(user.getId(), 10000L);
        return user;
    }

    private OrderInfo draft(UserModel user, ProductModel product) {
        OrderInfo order = orderFacade.create(user.getId(), List.of(new OrderItemCommand(product.getId(), 1L)));
        success(order.id());
        return order;
    }

    private Object confirm(UserModel user, OrderInfo order) {
        return orderConfirmFacade.confirm(user.getId(), order.id(), PgCommands.forOrder(order.id()));
    }

    private void allSucceeded(List<RequestResult> results, int count) {
        assertThat(count(results, Outcome.TECHNICAL_ERROR)).as("기술 오류 %s", results).isZero();
        assertThat(count(results, Outcome.BUSINESS_ERROR)).as("업무 오류 %s", results).isZero();
        assertThat(count(results, Outcome.SUCCESS)).isEqualTo(count);
    }

    @Test
    void confirmsOnlyOnce() throws Exception {
        UserModel user = customer();
        ProductModel product = productFixture.createProduct("상품", 2000L, 5L);
        OrderInfo order = draft(user, product);
        var pointsBefore = orderStateReader.pointHistoryRows();
        List<Request> requests = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            requests.add(new Request("same-" + i, () -> confirm(user, order)));
        }
        var results = runConcurrently(Set.of(), requests);
        allSucceeded(results, 4);
        assertThat(pg.state().counts().createReceived()).isEqualTo(1);
        assertThat(orderStateReader.orderRow(order.id())).isEqualTo(Arrays.asList(user.getId(), OrderStatus.CONFIRMED,
            2000L, 0L, 2000L, List.of(List.of(product.getId(), 1L, 2000L))));
        assertThat(orderStateReader.stockHistoryRows()).hasSize(1);
        assertThat(orderStateReader.stocks(List.of(product.getId()))).containsExactly(4L);
        assertThat(orderStateReader.pointHistoryRows()).isEqualTo(pointsBefore);
        assertThat(orderStateReader.balance(user.getId())).isEqualTo(10000L);
    }

    @Test
    void confirmsOnlyWhatBalanceAllows() throws Exception {
        UserModel user = customer();
        List<Request> requests = new ArrayList<>();
        List<OrderInfo> orders = new ArrayList<>();
        var before = orderStateReader.pointHistoryRows();
        for (int i = 0; i < 5; i++) {
            ProductModel product = productFixture.createProduct("상품" + i, 4000L, 5L);
            OrderInfo order = draft(user, product);
            orders.add(order);
            requests.add(new Request("order-" + i, () -> confirm(user, order)));
        }
        allSucceeded(runConcurrently(Set.of(), requests), 5);
        assertThat(orders).allSatisfy(order -> assertThat(orderFacade.getOrder(user.getId(), order.id()).status())
            .isEqualTo("CONFIRMED"));
        assertThat(pg.state().counts().createReceived()).isEqualTo(5);
        assertThat(orderStateReader.balance(user.getId())).isEqualTo(10000L);
        assertThat(orderStateReader.pointHistoryRows()).isEqualTo(before);
        assertThat(orderStateReader.stockHistoryRows()).hasSize(5);
    }

    @Test
    void keepsBothChargeAndPayment() throws Exception {
        UserModel user = customer();
        ProductModel product = productFixture.createProduct("상품", 7000L, 5L);
        OrderInfo order = draft(user, product);
        var before = orderStateReader.pointHistoryRows();
        allSucceeded(runConcurrently(Set.of(), List.of(
            new Request("charge", () -> pointFacade.charge(user.getId(), 2000L)),
            new Request("pg", () -> confirm(user, order)))), 2);
        assertThat(orderStateReader.balance(user.getId())).isEqualTo(12000L);
        var histories = orderStateReader.pointHistoryRows();
        assertThat(histories.subList(0, before.size())).isEqualTo(before);
        assertThat(histories.subList(before.size(), histories.size())).singleElement().satisfies(row ->
            assertThat(row.subList(2, 7)).isEqualTo(Arrays.asList(10000L, 12000L, 2000L, PointChangeCause.CHARGE, null)));
        assertThat(orderFacade.getOrder(user.getId(), order.id()).status()).isEqualTo("CONFIRMED");
    }

    @Test
    void confirmsOnlyAvailableStock() throws Exception {
        ProductModel product = productFixture.createProduct("상품", 1000L, 3L);
        List<Request> requests = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UserModel user = customer();
            OrderInfo order = draft(user, product);
            requests.add(new Request("stock-" + i, () -> confirm(user, order)));
        }
        var results = runConcurrently(Set.of(ErrorType.INSUFFICIENT_STOCK), requests);
        assertThat(count(results, Outcome.TECHNICAL_ERROR)).as("%s", results).isZero();
        assertThat(count(results, Outcome.SUCCESS)).isEqualTo(3);
        assertThat(count(results, Outcome.BUSINESS_ERROR)).isEqualTo(7);
        assertThat(pg.state().counts().createReceived()).isEqualTo(3);
        assertThat(orderStateReader.stocks(List.of(product.getId()))).containsExactly(0L);
        assertThat(orderStateReader.stockHistoryRows()).hasSize(3);
        assertThat(rowsOf(orderStateReader.pointHistoryRows(), PointChangeCause.ORDER_USE)).isEmpty();
    }

    @Test
    void keepsAdminChangeAndDeduction() throws Exception {
        ProductModel product = productFixture.createProduct("상품", 1000L, 10L);
        UserModel user = customer();
        OrderInfo order = draft(user, product);
        allSucceeded(runConcurrently(Set.of(), List.of(
            new Request("admin", () -> productFacade.changeStock(product.getId(), 50L)),
            new Request("pg", () -> confirm(user, order)))), 2);
        var rows = orderStateReader.stockHistoryRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get(2)).isEqualTo(10L);
        assertThat(rows.get(1).get(2)).isEqualTo(rows.get(0).get(3));
        assertThat(rowsOf(rows, StockChangeCause.ORDER_RESERVATION)).hasSize(1);
        assertThat(orderStateReader.stocks(List.of(product.getId()))).containsExactly((Long) rows.get(1).get(3));
        assertThat(orderStateReader.balance(user.getId())).isEqualTo(10000L);
    }

    @Test
    void confirmsOverlappingProductsWithoutDeadlock() throws Exception {
        ProductModel first = productFixture.createProduct("A", 1000L, 8L);
        ProductModel second = productFixture.createProduct("B", 2000L, 8L);
        List<Request> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            UserModel user = customer();
            List<OrderItemCommand> items = i % 2 == 0
                ? List.of(new OrderItemCommand(first.getId(), 1L), new OrderItemCommand(second.getId(), 1L))
                : List.of(new OrderItemCommand(second.getId(), 1L), new OrderItemCommand(first.getId(), 1L));
            OrderInfo order = orderFacade.create(user.getId(), items);
            success(order.id());
            requests.add(new Request("overlap-" + i, () -> confirm(user, order)));
        }
        allSucceeded(runConcurrently(Set.of(), requests), 8);
        assertThat(orderStateReader.stocks(List.of(first.getId(), second.getId()))).containsExactly(0L, 0L);
        assertThat(orderStateReader.stockHistoryRows()).hasSize(16);
        for (Long productId : List.of(first.getId(), second.getId())) {
            long previous = 8;
            for (var row : orderStateReader.stockHistoryRows().stream().filter(row -> row.get(1).equals(productId)).toList()) {
                assertThat(row.get(2)).isEqualTo(previous);
                assertThat(row.get(3)).isEqualTo(previous - 1);
                previous--;
            }
            assertThat(previous).isZero();
        }
        assertThat(rowsOf(orderStateReader.pointHistoryRows(), PointChangeCause.ORDER_USE)).isEmpty();
    }

    @Test
    void keepsProductUpdateAndDeduction() throws Exception {
        UserModel user = customer();
        ProductModel product = productFixture.createProduct("상품", 2000L, 5L);
        OrderInfo order = draft(user, product);
        allSucceeded(runConcurrently(Set.of(), List.of(
            new Request("update", () -> productFacade.update(product.getId(), "새 상품", 3000L)),
            new Request("pg", () -> confirm(user, order)))), 2);
        ProductModel stored = productJpaRepository.findById(product.getId()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("새 상품");
        assertThat(stored.getPrice().toWon()).isEqualTo(3000L);
        assertThat(stored.getStockQuantity()).isEqualTo(4L);
        assertThat(orderFacade.getOrder(user.getId(), order.id()).paidAmount()).isEqualTo(2000L);
        assertThat(orderStateReader.balance(user.getId())).isEqualTo(10000L);
    }

    @Test
    void keepsProductDeleteAndConfirmConsistent() throws Exception {
        UserModel user = customer();
        ProductModel product = productFixture.createProduct("상품", 2000L, 5L);
        OrderInfo order = draft(user, product);
        var before = orderStateReader.pointHistoryRows();
        var results = runConcurrently(Set.of(ErrorType.PRODUCT_NOT_FOUND), List.of(
            new Request("delete", () -> { productFacade.delete(product.getId()); return null; }),
            new Request("pg", () -> confirm(user, order))));
        assertThat(count(results, Outcome.TECHNICAL_ERROR)).as("%s", results).isZero();
        assertThat(results.get(0).outcome()).isEqualTo(Outcome.SUCCESS);
        ProductModel stored = productJpaRepository.findById(product.getId()).orElseThrow();
        assertThat(stored.getDeletedAt()).isNotNull();
        if (results.get(1).outcome() == Outcome.SUCCESS) {
            assertThat(stored.getStockQuantity()).isEqualTo(4L);
            assertThat(orderFacade.getOrder(user.getId(), order.id()).status()).isEqualTo("CONFIRMED");
            assertThat(pg.state().counts().createReceived()).isEqualTo(1);
            assertThat(orderStateReader.stockHistoryRows()).hasSize(1);
        } else {
            assertThat(results.get(1).errorType()).isEqualTo(ErrorType.PRODUCT_NOT_FOUND);
            assertThat(stored.getStockQuantity()).isEqualTo(5L);
            assertThat(orderFacade.getOrder(user.getId(), order.id()).status()).isEqualTo("DRAFT");
            assertThat(pg.state().counts().createReceived()).isZero();
            assertThat(orderStateReader.stockHistoryRows()).isEmpty();
        }
        assertThat(orderStateReader.pointHistoryRows()).isEqualTo(before);
        assertThat(orderStateReader.balance(user.getId())).isEqualTo(10000L);
    }
    private static boolean awaitWorkersTermination(ExecutorService executor) {
        try {
            return executor.awaitTermination(TERMINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
