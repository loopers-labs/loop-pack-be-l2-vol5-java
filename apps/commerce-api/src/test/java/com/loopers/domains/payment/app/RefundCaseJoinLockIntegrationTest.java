package com.loopers.domains.payment.app;

import com.loopers.common.error.InvalidValueException;
import com.loopers.common.error.RuleViolationException;
import com.loopers.domains.brand.app.BrandApplicationService;
import com.loopers.domains.order.app.OrderApplicationService;
import com.loopers.domains.payment.app.port.PaymentRefundCaseRepository;
import com.loopers.domains.payment.domain.RefundCase;
import com.loopers.domains.payment.domain.RefundCase.MoneyStatus;
import com.loopers.domains.payment.domain.RefundCase.Owner;
import com.loopers.domains.payment.out.PaymentRefundCasePersistenceAdapter;
import com.loopers.domains.payment.out.TestPgServer;
import com.loopers.domains.payment.out.TestPgServer.CreateBehavior;
import com.loopers.domains.product.app.ProductApplicationService;
import com.loopers.domains.user.out.UserJpaEntity;
import com.loopers.domains.user.out.UserJpaRepository;
import com.loopers.support.pg.MutablePaymentClock;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 늦은 성공 합류와 환불 추적이 같은 대상을 함께 바꾸는 순서를 테스트 설정의 저장소 훅으로 고정한다(버그 재현, PL P11-2).
// 합류 트랜잭션이 대상을 읽은 직전에, 다른 스레드의 환불 회차가 REFUND_REQUESTED를 commit하려 한다(취소 직전 중단 주입).
// 잠금이 없으면 회차가 먼저 끝나고 합류가 읽어 둔 확인 중(CHECKING)으로 덮어 "요청했을 수 있음" 기록이 사라진다.
// 잠금이 있으면 회차는 합류 commit까지 기다린다. 훅은 회차가 끝나거나 대상 행 잠금 SELECT에서 멈춘 것을 제한 시간 안에서 확인한 뒤 넘어간다.
// 늦은 성공 합류가 행 없는 대상을 FOR UPDATE로 읽으면 gap lock을 잡아, 서로 다른 두 주문의 INSERT가 데드락(1213)이 된다(QA 재현).
// 전용 테스트 설정이라 캐시에 남으면 DB 연결을 계속 붙잡는다(전체 검사에서 Too many connections). 클래스가 끝나면 닫는다.
@DirtiesContext
@SpringBootTest
@Import(RefundCaseJoinLockIntegrationTest.HookConfig.class)
class RefundCaseJoinLockIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private static final Instant EXPIRES_AT = NOW.plus(Duration.ofMinutes(2));
    private static final long FINISH_SECONDS = 10;
    private static final long POLL_MILLIS = 20;

    @Autowired private BrandApplicationService brands;
    @Autowired private ProductApplicationService products;
    @Autowired private OrderApplicationService orders;
    @Autowired private RefundTrackingService refundTracking;
    @Autowired private HookedRefundCases refundCases;
    @Autowired private MutablePaymentClock clock;
    @Autowired private TestPgFixture pg;
    @Autowired private UserJpaRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private TestPgServer server;
    private long productId;
    private long orderId;

    @BeforeEach
    void prepare() {
        clock.set(NOW);
        server = pg.start();
        users.save(new UserJpaEntity(1));
        long brand = brands.create("브랜드").id();
        productId = products.create(brand, "상품", 1000, 5).id();
        orderId = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1))).id();
    }

    @AfterEach
    void clean() throws Exception {
        refundCases.disarm();
        executor.shutdownNow();
        executor.awaitTermination(FINISH_SECONDS, TimeUnit.SECONDS);
        pg.close();
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("합류가 대상을 읽은 뒤 환불 회차가 환불 요청함을 commit하려 해도 합류가 그 상태를 확인 중으로 덮지 않고 다음 회차도 취소를 보내지 않는다")
    void joinDoesNotOverwriteRefundRequested() throws Exception {
        server.createBehavior(CreateBehavior.ACCEPT_PENDING);
        orders.confirm(1, orderId, TestPgFixture.command());
        clock.set(EXPIRES_AT);
        orders.expireOverduePayments();
        server.changeStatus(server.transactions().get(0).transactionKey(), "SUCCESS");
        // 합류가 다음 조회 시각을 바꿔야 대상 행 UPDATE가 나간다(같은 값이면 JPA가 갱신하지 않아 덮어쓰기가 드러나지 않는다).
        clock.set(EXPIRES_AT.plus(Duration.ofMinutes(10)));
        pg.crashBeforeCancel();
        AtomicReference<Future<?>> tracking = new AtomicReference<>();
        Thread joinThread = Thread.currentThread();
        AtomicReference<String> trackingState = new AtomicReference<>();
        refundCases.armOnceAfterLockedRead(joinThread, () -> {
            Future<?> submitted = executor.submit(refundTracking::trackDueCases);
            tracking.set(submitted);
            // 합류가 대상 행을 잠그고 있으면 회차는 끝나지 못한다. 회차가 그 잠금에서 멈춘 것을 본 뒤 합류를 계속 진행해 잠금을 푼다.
            trackingState.set(awaitDoneOrBlockedOnRefundCaseLock(submitted));
        });

        orders.reconcilePayment(orderId);

        assertThat(refundCases.hookFired(joinThread)).as("합류가 대상을 읽는 순간 훅이 실행됨").isTrue();
        assertThat(trackingState.get()).as("합류가 잠근 동안 회차가 대상 행 잠금에서 기다림").isEqualTo("blocked");
        assertThatThrownBy(() -> tracking.get().get(FINISH_SECONDS, TimeUnit.SECONDS))
            .as("회차는 REFUND_REQUESTED commit 뒤 취소 전에 멈춤")
            .hasCauseInstanceOf(TestPgFixture.InjectedCrash.class);
        assertThat(status()).isEqualTo(MoneyStatus.REFUND_REQUESTED);
        assertThat(pg.cancelCalls(providerOrderId())).isZero();

        pg.resumeCancel();
        clock.advance(Duration.ofMinutes(5));
        refundTracking.trackDueCases();

        assertThat(status()).isEqualTo(MoneyStatus.REFUND_REQUESTED);
        assertThat(pg.cancelCalls(providerOrderId())).as("요청했을 수 있는 대상에 취소를 다시 보내지 않음").isZero();
    }

    @Test
    @DisplayName("결제 실패한 서로 다른 두 주문에 늦은 성공이 동시에 합류해 대상 행이 없는 채로 읽어도 데드락 없이 둘 다 청구 확인·사람 담당(RESULT_REVERSED)이 된다")
    void joinsLateSuccessesOfDifferentOrdersWithoutDeadlock() throws Exception {
        server.createBehavior(CreateBehavior.ACCEPT_FAILED);
        long otherOrderId = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1))).id();
        orders.confirm(1, orderId, TestPgFixture.command());
        orders.confirm(1, otherOrderId, TestPgFixture.command());
        server.transactions().forEach(transaction -> server.changeStatus(transaction.transactionKey(), "SUCCESS"));
        CyclicBarrier bothRead = new CyclicBarrier(2);
        List<Long> orderIds = List.of(orderId, otherOrderId);
        List<Future<?>> joins = new ArrayList<>();
        // 두 합류가 모두 대상을 읽은 뒤에야 저장으로 넘어가게 해 "둘 다 읽고 둘 다 INSERT"하는 순서를 고정한다.
        for (long joiningOrderId : orderIds) {
            joins.add(executor.submit(() -> {
                refundCases.armOnce(Thread.currentThread(), () -> awaitBarrier(bothRead));
                return orders.reconcilePayment(joiningOrderId);
            }));
        }

        int succeeded = 0;
        int rejected = 0;
        List<String> technicalErrors = new ArrayList<>();
        try {
            for (Future<?> join : joins) {
                try {
                    join.get(FINISH_SECONDS, TimeUnit.SECONDS);
                    succeeded++;
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof RuleViolationException || e.getCause() instanceof InvalidValueException) {
                        rejected++;
                    } else {
                        technicalErrors.add(describe(e.getCause()));
                    }
                } catch (TimeoutException e) {
                    technicalErrors.add("timeout");
                }
            }
        } finally {
            bothRead.reset();
        }

        assertThat(succeeded + rejected + technicalErrors.size()).isEqualTo(2);
        assertThat(technicalErrors).as("기술 오류").isEmpty();
        assertThat(rejected).isZero();
        assertThat(succeeded).isEqualTo(2);
        for (long joinedOrderId : orderIds) {
            RefundCase refundCase = refundCases.findByOrderId(joinedOrderId).orElseThrow();
            assertThat(refundCase.getMoneyStatus()).isEqualTo(MoneyStatus.CHARGED);
            assertThat(refundCase.getOwner()).isEqualTo(Owner.PERSON);
            assertThat(refundCase.getOwnerReason()).isEqualTo(RefundCase.RESULT_REVERSED);
        }
    }

    // 회차가 끝나면 "done", 회차 연결의 대상 행 잠금 SELECT가 실행 중인 채 멈춰 있으면 "blocked"를 돌려준다.
    // 테스트 DB 사용자는 PROCESS 권한이 없어 innodb_trx·data_lock_waits를 못 읽는다. 같은 사용자의 연결은 PROCESSLIST로 보인다.
    // 합류 연결은 훅 동안 다음 문장을 보내지 않으므로(Sleep), 실행 중인 대상 FOR UPDATE는 회차의 잠금 대기다.
    private String awaitDoneOrBlockedOnRefundCaseLock(Future<?> submitted) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FINISH_SECONDS);
        while (System.nanoTime() < deadline) {
            if (submitted.isDone()) {
                return "done";
            }
            if (refundCaseLockWaiting()) {
                return "blocked";
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("회차가 제한 시간 안에 끝나지도 잠금에서 멈추지도 않았습니다.");
    }

    private boolean refundCaseLockWaiting() {
        return jdbc.queryForList("show full processlist").stream()
            .filter(row -> "Query".equals(row.get("Command")))
            .map(row -> String.valueOf(row.get("Info")).toLowerCase(Locale.ROOT))
            .anyMatch(info -> info.contains("from payment_refund_cases") && info.contains("for update"));
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(FINISH_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new IllegalStateException("두 합류가 제한 시간 안에 함께 대상을 읽지 못했습니다.", e);
        }
    }

    private static String describe(Throwable error) {
        StringBuilder description = new StringBuilder();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            description.append(cause.getClass().getSimpleName());
            if (cause instanceof SQLException sqlException) {
                description.append("(errorCode=").append(sqlException.getErrorCode()).append(')');
            }
            description.append(" > ");
        }
        return description.toString();
    }

    private MoneyStatus status() {
        return refundCases.findByOrderId(orderId).orElseThrow().getMoneyStatus();
    }

    private String providerOrderId() {
        return String.format("LP-ORD-%010d", orderId);
    }

    @TestConfiguration
    static class HookConfig {
        @Bean
        @Primary
        MutablePaymentClock joinLockClock() {
            return new MutablePaymentClock();
        }

        @Bean
        @Primary
        HookedRefundCases hookedRefundCases(PaymentRefundCasePersistenceAdapter delegate) {
            return new HookedRefundCases(delegate);
        }
    }

    // 지정한 스레드(합류를 부르는 스레드)가 대상을 처음 읽은 직후 그 스레드에서 한 번만 훅을 실행한다.
    // 잠금 읽기 훅은 존재 확인 같은 잠금 없는 읽기를 건너뛰고 대상 행 잠금(FOR UPDATE) 읽기 직후에만 실행한다.
    static class HookedRefundCases implements PaymentRefundCaseRepository {
        private final PaymentRefundCaseRepository delegate;
        private final Map<Thread, Hook> hooks = new ConcurrentHashMap<>();
        private final Set<Thread> firedThreads = ConcurrentHashMap.newKeySet();

        HookedRefundCases(PaymentRefundCaseRepository delegate) {
            this.delegate = delegate;
        }

        void armOnce(Thread thread, Runnable action) {
            firedThreads.remove(thread);
            hooks.put(thread, new Hook(action, false));
        }

        void armOnceAfterLockedRead(Thread thread, Runnable action) {
            firedThreads.remove(thread);
            hooks.put(thread, new Hook(action, true));
        }

        void disarm() {
            hooks.clear();
            firedThreads.clear();
        }

        boolean hookFired(Thread thread) {
            return firedThreads.contains(thread);
        }

        @Override
        public RefundCase save(RefundCase refundCase) {
            return delegate.save(refundCase);
        }

        @Override
        public Optional<RefundCase> findByOrderId(long orderId) {
            Optional<RefundCase> found = delegate.findByOrderId(orderId);
            fireIfArmed(false);
            return found;
        }

        @Override
        public boolean existsByOrderId(long orderId) {
            boolean exists = delegate.existsByOrderId(orderId);
            fireIfArmed(false);
            return exists;
        }

        @Override
        public Optional<RefundCase> findByOrderIdForUpdate(long orderId) {
            Optional<RefundCase> found = delegate.findByOrderIdForUpdate(orderId);
            fireIfArmed(true);
            return found;
        }

        @Override
        public List<Long> findDue(Instant now, int limit) {
            return delegate.findDue(now, limit);
        }

        private void fireIfArmed(boolean lockedRead) {
            Thread thread = Thread.currentThread();
            Hook hook = hooks.get(thread);
            if (hook == null || (hook.lockedReadOnly() && !lockedRead)) {
                return;
            }
            hooks.remove(thread);
            firedThreads.add(thread);
            hook.action().run();
        }

        private record Hook(Runnable action, boolean lockedReadOnly) {
        }
    }
}
