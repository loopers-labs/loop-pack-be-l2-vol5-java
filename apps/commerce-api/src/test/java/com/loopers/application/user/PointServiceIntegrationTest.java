package com.loopers.application.user;

import com.loopers.domain.user.PointsException;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
class PointServiceIntegrationTest {

    @Autowired
    private PointService pointService;

    @Autowired
    private FixtureUserInitializer initializer;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        initializer.initialize();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("API-07: 초기 잔액은 0이고 반복 충전의 성공마다 실제 MySQL 잔액이 증가한다.")
    @Test
    void chargesEverySuccessfulRequestAndReadsStoredBalance() {
        assertThat(pointService.balance("alice").balance()).isZero();
        assertThat(pointService.balance("bob").balance()).isZero();
        pointService.charge("alice", 2000L);

        assertThat(pointService.charge("alice", 3000L).balance()).isEqualTo(5000L);
        assertThat(pointService.charge("alice", 3000L).balance()).isEqualTo(8000L);
        assertAll(
            () -> assertThat(pointService.balance("alice").balance()).isEqualTo(8000L),
            () -> assertThat(storedBalance(1L)).isEqualTo(8000L),
            () -> assertThat(pointService.balance("bob").balance()).isZero()
        );
    }

    @DisplayName("API-08: 0·음수 충전은 MySQL 잔액과 감사 시각을 보존한다.")
    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void preservesStoredUserAfterInvalidCharge(long amount) {
        pointService.charge("alice", 2000L);
        var before = jdbcTemplate.queryForList("SELECT * FROM `user` ORDER BY id");

        assertAll(
            () -> assertThatThrownBy(() -> pointService.charge("alice", amount))
                .isInstanceOfSatisfying(PointsException.class,
                    error -> assertThat(error.getReason()).isEqualTo(PointsException.Reason.INVALID_AMOUNT)),
            () -> assertThat(jdbcTemplate.queryForList("SELECT * FROM `user` ORDER BY id")).isEqualTo(before)
        );
    }

    @DisplayName("API-08: 잔액 1에 Long 최댓값 충전을 거절하고 실제 저장값을 보존한다.")
    @Test
    void preservesBalanceAfterOverflow() {
        pointService.charge("alice", 1L);
        var before = jdbcTemplate.queryForList("SELECT * FROM `user` ORDER BY id");

        assertAll(
            () -> assertThatThrownBy(() -> pointService.charge("alice", Long.MAX_VALUE))
                .isInstanceOfSatisfying(PointsException.class,
                    error -> assertThat(error.getReason()).isEqualTo(PointsException.Reason.BALANCE_LIMIT_EXCEEDED)),
            () -> assertThat(jdbcTemplate.queryForList("SELECT * FROM `user` ORDER BY id")).isEqualTo(before),
            () -> assertThat(pointService.balance("alice").balance()).isEqualTo(1L)
        );
    }

    @DisplayName("POINT-PERSIST-01: MySQL BIGINT의 최댓값 잔액을 손실 없이 저장·조회한다.")
    @Test
    void roundTripsMaximumBalance() {
        pointService.charge("alice", Long.MAX_VALUE);

        assertAll(
            () -> assertThat(pointService.balance("alice").balance()).isEqualTo(Long.MAX_VALUE),
            () -> assertThat(storedBalance(1L)).isEqualTo(Long.MAX_VALUE)
        );
    }

    @DisplayName("POINT-PERSIST-02: 충전 UPDATE를 flush한 뒤 바깥 트랜잭션이 실패하면 잔액도 롤백한다.")
    @Test
    void rollsBackFlushedChargeWithCallerTransaction() {
        pointService.charge("alice", 2000L);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        IllegalStateException failure = new IllegalStateException("충전 저장 후 실패");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            pointService.charge("alice", 3000L);
            entityManager.flush();
            assertThat(storedBalance(1L)).isEqualTo(5000L);
            throw failure;
        })).isSameAs(failure);

        assertThat(pointService.balance("alice").balance()).isEqualTo(2000L);
        assertThat(storedBalance(1L)).isEqualTo(2000L);
    }

    @DisplayName("POINT-IDENTITY-01: 매핑이 있어도 사용자 DB 행이 없으면 자동 생성하지 않는다.")
    @Test
    void doesNotCreateMissingUserDuringRequest() {
        jdbcTemplate.update("DELETE FROM `user` WHERE id = 1");

        assertAll(
            () -> assertUserNotFound(() -> pointService.balance("alice")),
            () -> assertUserNotFound(() -> pointService.charge("alice", 3000L)),
            () -> assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE id = 1", Long.class)).isZero()
        );
    }

    @DisplayName("POINT-IDENTITY-02: 미등록·대소문자·공백이 다른 요청자는 저장값을 변경하지 않는다.")
    @ParameterizedTest
    @ValueSource(strings = {"unknown", "Alice", " alice", "alice ", "1"})
    void rejectsUnknownIdentityWithoutChangingRows(String requester) {
        var before = jdbcTemplate.queryForList("SELECT * FROM `user` ORDER BY id");

        assertAll(
            () -> assertUserNotFound(() -> pointService.balance(requester)),
            () -> assertUserNotFound(() -> pointService.charge(requester, 3000L)),
            () -> assertThat(jdbcTemplate.queryForList("SELECT * FROM `user` ORDER BY id")).isEqualTo(before)
        );
    }

    @DisplayName("POINT-IDENTITY-03: 식별값 누락·공백은 잔액 조회와 충전 모두 거절한다.")
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingIdentity(String requester) {
        assertAll(
            () -> assertThatThrownBy(() -> pointService.balance(requester))
                .isInstanceOfSatisfying(UserResolutionException.class,
                    error -> assertThat(error.getReason()).isEqualTo(UserResolutionException.Reason.INVALID_USER_ID)),
            () -> assertThatThrownBy(() -> pointService.charge(requester, 3000L))
                .isInstanceOfSatisfying(UserResolutionException.class,
                    error -> assertThat(error.getReason()).isEqualTo(UserResolutionException.Reason.INVALID_USER_ID)),
            () -> assertThat(storedBalance(1L)).isZero()
        );
    }

    @DisplayName("API-23: 같은 사용자의 동시 충전 두 건은 갱신 유실 없이 모두 반영한다.")
    @Test
    void serializesConcurrentCharges() throws Exception {
        pointService.charge("alice", 2000L);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return pointService.charge("alice", 3000L).balance();
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return pointService.charge("alice", 3000L).balance();
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(5000L, 8000L);
        }
        assertThat(pointService.balance("alice").balance()).isEqualTo(8000L);
        assertThat(storedBalance(1L)).isEqualTo(8000L);
    }

    private long storedBalance(long userId) {
        return jdbcTemplate.queryForObject("SELECT point_balance FROM `user` WHERE id = ?", Long.class, userId);
    }

    private void assertUserNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(UserResolutionException.class,
                error -> assertThat(error.getReason()).isEqualTo(UserResolutionException.Reason.USER_NOT_FOUND));
    }
}
