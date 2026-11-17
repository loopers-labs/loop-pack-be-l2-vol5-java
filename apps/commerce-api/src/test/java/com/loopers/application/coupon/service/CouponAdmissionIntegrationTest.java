package com.loopers.application.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.loopers.application.coupon.command.CouponCampaignCommand;
import com.loopers.application.coupon.command.CouponIssueCommand;
import com.loopers.application.coupon.port.CouponTransactionObserver;
import com.loopers.application.coupon.port.CouponExecutionEvidence;
import com.loopers.application.coupon.result.CouponIssueResult;
import com.loopers.application.coupon.support.CouponAdmissionControl;
import com.loopers.application.coupon.support.CouponExecution;
import com.loopers.application.coupon.support.CouponSlotBusyException;
import com.loopers.application.coupon.support.CouponSlotRetryTiming;
import com.loopers.application.coupon.support.CouponAdmissionException.Code;
import com.loopers.application.coupon.support.CouponAdmissionException;
import com.loopers.domain.support.error.DomainException;
import com.loopers.infrastructure.persistence.coupon.support.SpringCouponTransactionObserver;
import com.loopers.infrastructure.concurrency.coupon.SemaphoreCouponAdmission;
import com.loopers.config.coupon.CouponAdmissionProperties;
import com.loopers.support.test.IntegrationTest;
import com.loopers.utils.DatabaseCleanUp;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@Import(CouponAdmissionIntegrationTest.ObserverConfiguration.class)
// 공통 Testcontainers fixture는 일반 test 계정이다. 종료 증거 검증 컨텍스트만 container의 관리자 계정을 사용한다.
@TestPropertySource(properties = {"datasource.mysql-jpa.main.username=root", "datasource.mysql-jpa.main.password=test"})
class CouponAdmissionIntegrationTest {
    private IssueCouponFacade facade;
    private CouponAdmissionControl control;
    @Autowired private CouponIssueLookupService lookup;
    @Autowired private CouponExecutionEvidence evidence;
    @Autowired private CouponAdmissionProperties properties;
    @Autowired private CouponSlotRetryTiming timing;
    @Autowired @Qualifier("mySqlMainDataSource") private HikariDataSource dataSource;
    @Autowired private PrepareCouponCampaignService preparation;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AtomicBoolean forceUnknown;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private IssueCouponService issuance;

    @BeforeEach
    void setUp() {
        // 테스트에는 외부 TX가 없다. 실제 Spring TX 서비스/증거 adapter를 유지하며 실행 registry만 사례별 격리한다.
        control = new CouponAdmissionControl(new SemaphoreCouponAdmission(properties), evidence);
        facade = new IssueCouponFacade(lookup, issuance, control, timing);
    }

    @AfterEach
    void tearDown() {
        forceUnknown.set(false);
        control.close("test-cleanup");
        cleanUp.truncateAllTables();
    }

    @Test
    void threeRealPermitsHoldFourthOutsideDatabaseAndReleaseAfterCommit() throws Exception {
        long campaign = campaign(4);
        open();
        CountDownLatch entered = new CountDownLatch(3);
        Semaphore first = new Semaphore(0);
        Semaphore others = new Semaphore(0);
        doAnswer(invocation -> {
            CouponIssueCommand command = invocation.getArgument(0);
            if (command.userId() <= 3) {
                entered.countDown();
                if (!(command.userId() == 1 ? first : others).tryAcquire(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test barrier deadline exceeded");
                }
            }
            return invocation.callRealMethod();
        }).when(issuance).execute(any(CouponIssueCommand.class), any(CouponExecution.class));

        var executor = Executors.newFixedThreadPool(3);
        try {
            Future<CouponIssueResult> one = executor.submit(() -> facade.execute(new CouponIssueCommand(campaign, 1L)));
            Future<CouponIssueResult> two = executor.submit(() -> facade.execute(new CouponIssueCommand(campaign, 2L)));
            Future<CouponIssueResult> three = executor.submit(() -> facade.execute(new CouponIssueCommand(campaign, 3L)));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(control.status().running()).isEqualTo(3);
            assertThat(control.status().available()).isZero();
            assertThatThrownBy(() -> facade.execute(new CouponIssueCommand(campaign, 4L)))
                .isInstanceOfSatisfying(CouponAdmissionException.class,
                    failure -> assertThat(failure.getErrorCode()).isEqualTo(Code.COUPON_CAPACITY_EXCEEDED));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupons", Long.class)).isZero();
            first.release();
            CouponIssueResult issued = one.get(10, TimeUnit.SECONDS);
            assertThat(control.status().available()).isEqualTo(1);
            assertThat(facade.execute(new CouponIssueCommand(campaign, 4L)).created()).isTrue();
            control.close("manual");
            assertThat(facade.execute(new CouponIssueCommand(campaign, 1L)).couponId()).isEqualTo(issued.couponId());
            others.release(2);
            two.get(10, TimeUnit.SECONDS);
            three.get(10, TimeUnit.SECONDS);
            assertThat(control.status().available()).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupons", Long.class)).isEqualTo(4L);
            assertThat(jdbc.queryForObject("SELECT issued_count FROM coupon_campaigns WHERE id = ?", Integer.class, campaign))
                .isEqualTo(4);
        } finally {
            first.release();
            others.release(3);
            executor.shutdown();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void confirmedRollbackReturnsPermitAndDoesNotCloseAdmission() {
        long campaign = campaign(1);
        preparation.execute(new CouponCampaignCommand.Activation(campaign, false));
        open();

        assertThatThrownBy(() -> facade.execute(new CouponIssueCommand(campaign, 11L))).isInstanceOf(DomainException.class);

        assertThat(control.status().available()).isEqualTo(3);
        assertThat(control.status().open()).isTrue();
        assertThat(control.status().executions()).allSatisfy(execution -> assertThat(execution.released()).isTrue());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupons", Long.class)).isZero();
    }

    @Test
    void retriesRealLockedSlotAfterConfirmedEmptyTransactionEndsAndHolderReleases() throws Exception {
        long campaign = campaign(1);
        open();
        Set<String> previousExecutions = executionIds();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch unlock = new CountDownLatch(1);
        CountDownLatch holderEnded = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        var executor = Executors.newSingleThreadExecutor();
        Future<?> holder = executor.submit(() -> {
            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbc.queryForList("SELECT id FROM coupon_slots WHERE campaign_id = ? FOR UPDATE", campaign);
                    locked.countDown();
                    try {
                        if (!unlock.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Slot holder deadline exceeded");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                });
            } finally {
                holderEnded.countDown();
            }
        });
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            doAnswer(invocation -> {
                attempts.incrementAndGet();
                try {
                    return invocation.callRealMethod();
                } catch (CouponSlotBusyException busy) {
                    // 실제 발급/재조회 TX가 모두 끝난 프록시 반환 지점에서만 holder를 종료시킨다.
                    unlock.countDown();
                    assertThat(holderEnded.await(10, TimeUnit.SECONDS)).isTrue();
                    throw busy;
                }
            }).when(issuance).execute(any(CouponIssueCommand.class), any(CouponExecution.class));

            assertThat(facade.execute(new CouponIssueCommand(campaign, 11L)).created()).isTrue();

            holder.get(10, TimeUnit.SECONDS);
            assertThat(attempts.get()).isBetween(2, 4);
            assertThat(control.status().executions().stream()
                .filter(execution -> !previousExecutions.contains(execution.executionId())).toList()).hasSize(1);
            assertThat(control.status().available()).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupons", Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT issued_count FROM coupon_campaigns WHERE id = ?", Integer.class, campaign))
                .isEqualTo(1);
        } finally {
            unlock.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void unknownCompletionHoldsPermitUntilOriginalDatabaseSessionDisappearsAndExplicitRecovery() throws Exception {
        long campaign = campaign(1);
        open();
        forceUnknown.set(true);

        assertThatThrownBy(() -> facade.execute(new CouponIssueCommand(campaign, 11L)))
            .isInstanceOfSatisfying(CouponAdmissionException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(Code.COUPON_ISSUE_OUTCOME_UNKNOWN));

        assertThat(control.status().available()).isEqualTo(2);
        assertThat(control.status().held()).isEqualTo(1);
        assertThat(control.status().open()).isFalse();
        forceUnknown.set(false);
        assertThat(facade.execute(new CouponIssueCommand(campaign, 11L)).created()).isFalse();
        String id = control.status().executions().stream().filter(execution -> !execution.released())
            .findFirst().orElseThrow().executionId();
        assertThat(control.recover(control.status().bootId(), List.of(id)).getFirst().result())
            .isEqualTo(CouponAdmissionControl.Recovery.NOT_ENDED);
        long originalConnection = control.status().executions().stream()
            .filter(execution -> execution.executionId().equals(id)).findFirst().orElseThrow()
            .transactions().getFirst().work().connectionId();
        // 이 테스트의 Testcontainers DB에서 이 테스트가 기록한 원 세션 하나만 종료한다.
        terminateAndEvictOwnedSession(originalConnection);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        CouponAdmissionControl.Recovery recovery;
        do {
            recovery = control.recover(control.status().bootId(), List.of(id)).getFirst().result();
            if (recovery != CouponAdmissionControl.Recovery.RELEASED) {
                Thread.sleep(50);
            }
        } while (recovery != CouponAdmissionControl.Recovery.RELEASED && System.nanoTime() < deadline);
        assertThat(recovery).isEqualTo(CouponAdmissionControl.Recovery.RELEASED);
        assertThat(control.status().available()).isEqualTo(3);
        assertThat(control.status().open()).isFalse();
        control.open(control.status().bootId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupons", Long.class)).isEqualTo(1L);
    }

    private void open() {
        control.verifyRestart(control.status().bootId(), true, "test-no-prior-process", true, List.of());
        control.open(control.status().bootId());
    }

    private Set<String> executionIds() {
        return control.status().executions().stream().map(CouponAdmissionControl.ExecutionView::executionId)
            .collect(Collectors.toSet());
    }

    private void terminateAndEvictOwnedSession(long originalConnection) throws Exception {
        List<Connection> leases = new ArrayList<>();
        Connection original = null;
        try {
            // 원 lease를 반환하지 않은 상태로 확보해 KILL 뒤 stale connection이 다시 대여되지 않게 한다.
            for (int index = 0; index < dataSource.getMaximumPoolSize(); index++) {
                Connection lease = dataSource.getConnection();
                leases.add(lease);
                try (Statement statement = lease.createStatement();
                     var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
                    assertThat(result.next()).isTrue();
                    if (result.getLong(1) == originalConnection) {
                        original = lease;
                        break;
                    }
                }
            }
            assertThat(original).as("test-owned original pooled connection").isNotNull();
            String url = original.getMetaData().getURL();
            try (Connection admin = DriverManager.getConnection(url, "root", "test");
                 Statement statement = admin.createStatement()) {
                statement.execute("KILL CONNECTION " + originalConnection);
            } finally {
                dataSource.evictConnection(original);
            }
        } finally {
            SQLException cleanupFailure = null;
            for (Connection lease : leases) {
                try {
                    lease.close();
                } catch (SQLException failure) {
                    if (cleanupFailure == null) {
                        cleanupFailure = failure;
                    } else {
                        cleanupFailure.addSuppressed(failure);
                    }
                }
            }
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        }
    }

    private long campaign(int quota) {
        Instant now = Instant.now();
        long id = preparation.execute(new CouponCampaignCommand.Create("수용 검증", quota, "1000원 할인", 1000L,
            now.minusSeconds(3600), now.plusSeconds(3600))).campaignId();
        preparation.execute(new CouponCampaignCommand.Activation(id, true));
        return id;
    }

    @TestConfiguration
    static class ObserverConfiguration {
        @Bean AtomicBoolean forceUnknown() { return new AtomicBoolean(); }

        @Bean
        @Primary
        CouponSlotRetryTiming deterministicRetryClock() {
            // 실 DB/TX/permit과 실제 50ms 대기는 유지한다. 경과 예산은 별도 단위테스트에서 검증한다.
            return new CouponSlotRetryTiming() {
                @Override public long now() { return 0L; }
            };
        }

        @Bean
        @Primary
        CouponTransactionObserver observer(SpringCouponTransactionObserver delegate, AtomicBoolean forceUnknown) {
            return execution -> {
                delegate.observe(execution);
                if (execution != null && forceUnknown.get()) {
                    int index = execution.transactions().size() - 1;
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            execution.completed(index, CouponExecution.Completion.UNKNOWN);
                        }
                    });
                }
            };
        }
    }
}
