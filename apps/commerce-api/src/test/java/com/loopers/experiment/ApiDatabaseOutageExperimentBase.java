package com.loopers.experiment;

import com.loopers.application.order.facade.OrderPaymentFacade;
import com.loopers.application.order.facade.OrderPaymentFacade.ConfirmCommand;
import com.loopers.domain.brand.entity.Brand;
import com.loopers.domain.order.entity.Order;
import com.loopers.domain.order.vo.OrderLine;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.product.entity.Product;
import com.loopers.support.kafka.OrderEventsTopicReader;
import com.loopers.support.pg.TestPgServer;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * commerce-api 에 드라이버 timeout 을 둘지 판단하기 위한 실제 DB 장애 실험 (6주차 D-98 후속). 제품 코드가 아니며 결정 근거로 씀.
 * 결제 시작(TX A) · PG 결과 적용(TX B) 도중 MySQL 을 docker pause 로 40초 멈추고, 요청이 언제 어떻게 끝나는지와
 * 장애 뒤 같은 요청을 다시 보냈을 때 주문 · 결제 · 이벤트가 어떻게 되는지 기록함. timeout 유무는 하위 클래스의 속성으로 나눔
 */
abstract class ApiDatabaseOutageExperimentBase {

    private static final Logger log = LoggerFactory.getLogger(ApiDatabaseOutageExperimentBase.class);
    private static final Long USER_ID = 1L;
    private static final Duration OUTAGE = Duration.ofSeconds(40);
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(60);

    private record Result(String scene, String firstOutcome, long firstMillis, String afterOutage, String retryOutcome,
                          String finalState, int pgCreates, long events) {}

    @Autowired
    private OrderPaymentFacade orderPaymentFacade;

    @Autowired
    private OrderEventsTopicReader topicReader;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final TestPgServer pg = TestPgServer.shared();

    protected abstract String mode();

    @AfterEach
    void tearDown() {
        unpause(mysql());
        databaseCleanUp.truncateAllTables();
    }

    private static GenericContainer<?> mysql() {
        return MySqlTestContainersConfig.container().orElseThrow();
    }

    private static void pause(GenericContainer<?> mysql) {
        mysql.getDockerClient().pauseContainerCmd(mysql.getContainerId()).exec();
    }

    private static void unpause(GenericContainer<?> mysql) {
        Boolean paused = mysql.getDockerClient().inspectContainerCmd(mysql.getContainerId()).exec().getState().getPaused();
        if (Boolean.TRUE.equals(paused)) {
            mysql.getDockerClient().unpauseContainerCmd(mysql.getContainerId()).exec();
        }
    }

    private Order draft() {
        return transactionTemplate.execute(status -> {
            Brand brand = new Brand("브랜드", null);
            entityManager.persist(brand);
            Product product = new Product(brand, "상품", 1_000L);
            product.changeStock(10);
            entityManager.persist(product);
            Order order = Order.draft(USER_ID, List.of(new OrderLine(product.getId(), product.getName(), product.getPrice(), 1)),
                ZonedDateTime.now());
            entityManager.persist(order);
            return order;
        });
    }

    private ConfirmCommand command(Order order) {
        return new ConfirmCommand("attempt-" + order.getId(), "TEST", "0000-0000-0000-0000");
    }

    /** 주문 상태 / 결제 상태 / 예약 상태. 장애 TX 와 별개의 커넥션으로 읽음 */
    private String stateOf(Order order) {
        EntityManager reader = entityManagerFactory.createEntityManager();
        try {
            Object[] row = (Object[]) reader.createNativeQuery(
                "select o.status, coalesce(p.status, '-'), coalesce(p.reservation_status, '-') from orders o "
                    + "left join payment p on p.order_id = o.id where o.id = " + order.getId()).getSingleResult();
            return row[0] + "/" + row[1] + "/" + row[2];
        } finally {
            reader.close();
        }
    }

    private static String describe(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String name = failure.getClass().getSimpleName();
        return root == failure ? name : name + " ← " + root.getClass().getSimpleName();
    }

    private interface Call {
        void run();
    }

    /** DB 를 멈춘 채 요청을 보내고, OUTAGE 뒤 DB 를 풀어 요청이 끝나기까지의 결과와 시간을 잼 */
    private String[] callDuringOutage(Call call, Duration pauseAfter) throws Exception {
        Instant started = Instant.now();
        CompletableFuture<Void> request = CompletableFuture.runAsync(call::run);
        Thread.sleep(pauseAfter.toMillis());
        pause(mysql());
        String outcome;
        long millis;
        try {
            request.get(OUTAGE.toMillis(), TimeUnit.MILLISECONDS);
            outcome = "성공";
        } catch (TimeoutException e) {
            outcome = "장애 중 응답 없음";
        } catch (Exception e) {
            outcome = describe(e.getCause() == null ? e : e.getCause());
        }
        unpause(mysql());
        if (outcome.equals("장애 중 응답 없음")) {
            try {
                request.get(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
                outcome = "장애 중 응답 없음 → 복구 뒤 성공";
            } catch (Exception e) {
                outcome = "장애 중 응답 없음 → 복구 뒤 " + describe(e.getCause() == null ? e : e.getCause());
            }
        }
        millis = Duration.between(started, Instant.now()).toMillis();
        return new String[] {outcome, String.valueOf(millis)};
    }

    private long eventsOf(Order order, Map<TopicPartition, Long> before) {
        return topicReader.recordsSince(before).stream().filter(r -> r.key().equals(String.valueOf(order.getId()))).count();
    }

    private Result startDuringOutage() throws Exception {
        pg.reset();
        Order order = draft();
        Map<TopicPartition, Long> before = topicReader.endOffsets();
        int createsBefore = pg.createCount();
        String[] first = callDuringOutage(() -> orderPaymentFacade.confirm(USER_ID, order.getId(), command(order)), Duration.ZERO);
        String afterOutage = stateOf(order);
        String retry;
        try {
            orderPaymentFacade.confirm(USER_ID, order.getId(), command(order));
            retry = "성공";
        } catch (Exception e) {
            retry = describe(e);
        }
        return new Result("결제 시작(TX A) 중 응답 없음 40초", first[0], Long.parseLong(first[1]), afterOutage, retry,
            stateOf(order), pg.createCount() - createsBefore, eventsOf(order, before));
    }

    private Result applyDuringOutage() throws Exception {
        pg.reset();
        Order order = draft();
        orderPaymentFacade.confirm(USER_ID, order.getId(), command(order));
        pg.complete(pg.transactionsOf(Payment.providerOrderIdOf(order.getId())).get(0).transactionKey(), "SUCCESS");
        Map<TopicPartition, Long> before = topicReader.endOffsets();
        int createsBefore = pg.createCount();
        // 조회 응답을 조회 read timeout(1s)보다 짧게 늦춰 그 사이 DB 를 멈춤. 첫 읽기(snapshot)는 지나고 결과 적용(TX B)이 멈춘 DB 를 만남
        pg.lookupWillDelay(Duration.ofMillis(400));
        String[] first = callDuringOutage(() -> orderPaymentFacade.reconcile(USER_ID, order.getId()), Duration.ofMillis(150));
        pg.lookupNormally();
        String afterOutage = stateOf(order);
        String retry;
        try {
            orderPaymentFacade.reconcile(USER_ID, order.getId());
            retry = "성공";
        } catch (Exception e) {
            retry = describe(e);
        }
        return new Result("PG 결과 적용(TX B) 중 응답 없음 40초", first[0], Long.parseLong(first[1]), afterOutage, retry,
            stateOf(order), pg.createCount() - createsBefore, eventsOf(order, before));
    }

    @DisplayName("DB 가 응답하지 않을 때 결제 시작 · 결과 적용 요청이 끝나는 방식과 장애 뒤 다시 보낸 요청의 결과")
    @Test
    void measure() throws Exception {
        List<Result> results = new ArrayList<>();
        results.add(startDuringOutage());
        results.add(applyDuringOutage());

        StringBuilder report = new StringBuilder("# api DB 장애 실험 (" + mode() + ")\n\n");
        report.append("| 장면 | 첫 요청 결과 | 첫 요청 소요(ms) | 장애 직후 주문/결제/예약 | 같은 요청 재시도 | 최종 주문/결제/예약 | PG 생성 추가 | 이벤트 |\n");
        report.append("|---|---|---|---|---|---|---|---|\n");
        for (Result r : results) {
            report.append("| ").append(r.scene()).append(" | ").append(r.firstOutcome()).append(" | ").append(r.firstMillis())
                .append(" | ").append(r.afterOutage()).append(" | ").append(r.retryOutcome()).append(" | ").append(r.finalState())
                .append(" | ").append(r.pgCreates()).append(" | ").append(r.events()).append(" |\n");
        }
        Path path = Path.of("build", "experiment", "api-database-outage-" + mode() + ".md");
        writeReport(path, report.toString());
        log.info("[api DB 장애]\n{}", report);
    }

    private static void writeReport(Path path, String report) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, report);
    }
}
