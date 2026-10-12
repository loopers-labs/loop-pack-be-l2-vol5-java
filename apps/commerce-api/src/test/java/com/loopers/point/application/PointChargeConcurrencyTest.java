package com.loopers.point.application;

import com.loopers.point.adapter.out.persistence.PointGroupJpaRepository;
import com.loopers.point.application.port.in.PointCommandUseCase;
import com.loopers.support.concurrency.ConcurrentRunner;
import com.loopers.support.concurrency.ConcurrentRunner.Kind;
import com.loopers.support.concurrency.ConcurrentRunner.Outcome;
import com.loopers.user.adapter.out.persistence.UserJpaRepository;
import com.loopers.user.domain.UserModel;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

import static com.loopers.support.concurrency.ConcurrentRunner.count;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * W3 R-7 · ADR-W3-02: 같은 사용자의 충전은 남은 그룹 행을 잠가 한 줄로 선다.
 * 순서에 따라 누가 성공하는지는 달라지지만 건수와 최종 잔액은 정해져 있다. 잠금이 실제로 나가는지는 LockingSqlIntegrationTest가 본다.
 */
@SpringBootTest
class PointChargeConcurrencyTest {

    private static final String OVER_LIMIT = "보유할 수 있는 범위를 넘습니다";
    private static final int REQUESTS = 8;

    @Autowired
    private PointCommandUseCase pointCommandUseCase;

    @Autowired
    private PointQueryService pointQueryService;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private PointGroupJpaRepository pointGroupJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("T-17·PNT-03 잔액 (Long 최댓값 − 10)에서 6원 충전 8건을 동시에 → 충전 1 · 상한 초과 409 7 · 기술 오류 0 · 최종 잔액 (Long 최댓값 − 4)")
    @Test
    void chargeLimitRace() throws Exception {
        // arrange
        UserModel user = userJpaRepository.save(new UserModel("고객"));
        pointCommandUseCase.charge(user.getId(), Long.MAX_VALUE - 10);

        // act
        List<Callable<?>> charges = Collections.nCopies(REQUESTS, () -> pointCommandUseCase.charge(user.getId(), 6));
        List<Outcome> outcomes = ConcurrentRunner.run(charges);

        // assert: 요청 결과
        assertThat(count(outcomes, Kind.SUCCESS)).isEqualTo(1);
        assertThat(outcomes.stream().filter(outcome -> outcome.rejectedWith(OVER_LIMIT)).count()).isEqualTo(REQUESTS - 1);
        assertThat(count(outcomes, Kind.TECHNICAL_ERROR)).isZero();

        // assert: 새로 읽은 DB — 거절된 충전은 그룹을 남기지 않았다
        assertThat(pointGroupJpaRepository.count()).isEqualTo(2);
        assertThat(pointQueryService.getBalance(user.getId()).balance()).isEqualTo(Long.MAX_VALUE - 4);
    }

    @DisplayName("T-18 충전한 적 없는 사용자에게 1,000원 충전 8건을 동시에 → 잠글 행이 없어 일부는 교착으로 끝난다. 성공 1건 이상 · 실패는 모두 잠금 실패 · 최종 잔액 = 성공 건수 × 1,000")
    @Test
    void firstChargeRace() throws Exception {
        // arrange: 그룹이 하나도 없는 사용자
        UserModel user = userJpaRepository.save(new UserModel("고객"));

        // act
        List<Callable<?>> charges = Collections.nCopies(REQUESTS, () -> pointCommandUseCase.charge(user.getId(), 1_000));
        List<Outcome> outcomes = ConcurrentRunner.run(charges);

        // assert: 요청 결과
        long succeeded = count(outcomes, Kind.SUCCESS);
        assertThat(succeeded).isPositive();
        assertThat(outcomes.stream().filter(outcome -> outcome.kind() != Kind.SUCCESS))
            .allSatisfy(outcome -> assertThat(outcome.error()).isInstanceOf(PessimisticLockingFailureException.class));

        // assert: 새로 읽은 DB — 실패한 충전은 그룹을 남기지 않았다
        assertThat(pointGroupJpaRepository.count()).isEqualTo(succeeded);
        assertThat(pointQueryService.getBalance(user.getId()).balance()).isEqualTo(succeeded * 1_000);
    }
}
