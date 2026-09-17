package com.loopers.application.point;

import com.loopers.domain.common.Money;
import com.loopers.domain.point.PointGroup;
import com.loopers.domain.point.PointHistory;
import com.loopers.domain.point.PointHistoryRepository;
import com.loopers.domain.point.PointHistoryType;
import com.loopers.infrastructure.point.PointGroupJpaRepository;
import com.loopers.infrastructure.point.PointHistoryJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doCallRealMethod;

/**
 * 포인트 만료 처리 (PNT-08, ADR-10, 8-1). 스케줄러 시각을 기다리지 않고 Facade를 기준 시각과 함께 직접 부른다.
 */
@SpringBootTest
class PointExpirationFacadeIntegrationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Period VALIDITY = Period.ofYears(5);
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 6, 1, 0, 0, 0, 0, SEOUL);

    @Autowired
    private PointExpirationFacade pointExpirationFacade;

    @MockitoSpyBean
    private PointHistoryRepository pointHistoryRepository;

    @Autowired
    private PointGroupJpaRepository pointGroupJpaRepository;

    @Autowired
    private PointHistoryJpaRepository pointHistoryJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private PointGroup chargedAt(long userId, long amount, ZonedDateTime chargedAt) {
        return pointGroupJpaRepository.save(PointGroup.charge(userId, Money.of(amount), chargedAt, VALIDITY));
    }

    /** 기준 시각(NOW)에 이미 만료된 그룹. 2020-01-01 충전 → 2025-01-01 만료. */
    private PointGroup expiredGroup(long userId, long amount) {
        return chargedAt(userId, amount, ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, SEOUL));
    }

    private Money remainingOf(PointGroup group) {
        return pointGroupJpaRepository.findById(group.getId()).orElseThrow().getRemaining();
    }

    private List<PointHistory> expireHistories() {
        return pointHistoryJpaRepository.findAll().stream()
            .filter(history -> history.getType() == PointHistoryType.EXPIRE)
            .toList();
    }

    @DisplayName("만료 처리를 실행하면, ")
    @Nested
    class ExpireAll {

        @DisplayName("PNT-08 만료된 그룹(4,000원, 3,000원)만 남은 금액을 0으로 만들고 만료 이력(-남은 금액)을 남기며, 유효한 그룹(5,000원)은 그대로다.")
        @Test
        void expiresOnlyExpiredGroups() {
            // arrange
            PointGroup expiredA = expiredGroup(1L, 4_000);
            PointGroup usableB = chargedAt(1L, 5_000, ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, SEOUL));
            PointGroup expiredC = expiredGroup(2L, 3_000);

            // act
            int expired = pointExpirationFacade.expireAll(NOW);

            // assert
            assertThat(expired).isEqualTo(2);
            assertThat(remainingOf(expiredA)).isEqualTo(Money.of(0));
            assertThat(remainingOf(expiredC)).isEqualTo(Money.of(0));
            assertThat(remainingOf(usableB)).isEqualTo(Money.of(5_000));
            assertThat(expireHistories())
                .extracting(PointHistory::getGroupId, PointHistory::getUserId, PointHistory::getAmount)
                .containsExactlyInAnyOrder(
                    tuple(expiredA.getId(), 1L, -4_000L),
                    tuple(expiredC.getId(), 2L, -3_000L)
                );
        }

        @DisplayName("P-20 만료 시각이 기준 시각과 같은 그룹은 만료하고, 1초 뒤에 만료되는 그룹은 그대로다.")
        @Test
        void expiresFromExactExpiryTime() {
            // arrange
            PointGroup atNow = chargedAt(1L, 1_000, NOW.minus(VALIDITY));
            PointGroup oneSecondLater = chargedAt(1L, 2_000, NOW.minus(VALIDITY).plusSeconds(1));

            // act
            int expired = pointExpirationFacade.expireAll(NOW);

            // assert
            assertThat(expired).isEqualTo(1);
            assertThat(remainingOf(atNow)).isEqualTo(Money.of(0));
            assertThat(remainingOf(oneSecondLater)).isEqualTo(Money.of(2_000));
        }

        @DisplayName("PNT-08 같은 기준 시각으로 다시 실행하면 처리할 그룹이 없고, 만료 이력도 늘지 않는다.")
        @Test
        void isIdempotent() {
            // arrange
            expiredGroup(1L, 4_000);
            pointExpirationFacade.expireAll(NOW);

            // act
            int expiredAgain = pointExpirationFacade.expireAll(NOW);

            // assert
            assertThat(expiredAgain).isZero();
            assertThat(expireHistories()).hasSize(1);
        }
    }

    @DisplayName("대상을 묶음으로 나눠 처리할 때, ")
    @Nested
    class Chunk {

        @DisplayName("ADR-10 만료된 그룹 5개를 2개씩 처리하면 세 번의 묶음으로 5개를 모두 만료한다.")
        @Test
        void processesAllChunks() {
            // arrange
            List<PointGroup> groups = IntStream.rangeClosed(1, 5).mapToObj(i -> expiredGroup(i, 1_000)).toList();

            // act
            int expired = pointExpirationFacade.expireAll(NOW, 2);

            // assert
            assertThat(expired).isEqualTo(5);
            assertThat(groups).allSatisfy(group -> assertThat(remainingOf(group)).isEqualTo(Money.of(0)));
            assertThat(expireHistories()).hasSize(5);
        }

        @DisplayName("ADR-10 두 번째 묶음에서 실패하면 첫 묶음(2개)만 커밋되어 남고, 다시 실행하면 나머지 3개만 처리한다.")
        @Test
        void keepsCommittedChunkAndResumes() {
            // arrange
            List<PointGroup> groups = IntStream.rangeClosed(1, 5).mapToObj(i -> expiredGroup(i, 1_000)).toList();
            doCallRealMethod()
                .doThrow(new IllegalStateException("만료 이력 저장 실패"))
                .doCallRealMethod()
                .when(pointHistoryRepository).saveAll(anyList());

            // act & assert: 두 번째 묶음에서 실패
            assertThatThrownBy(() -> pointExpirationFacade.expireAll(NOW, 2))
                .isInstanceOf(IllegalStateException.class);
            assertThat(groups).filteredOn(group -> remainingOf(group).isZero()).hasSize(2);
            assertThat(expireHistories()).hasSize(2);

            // act & assert: 다시 실행하면 남은 3개만
            int expired = pointExpirationFacade.expireAll(NOW, 2);
            assertThat(expired).isEqualTo(3);
            assertThat(groups).allSatisfy(group -> assertThat(remainingOf(group)).isEqualTo(Money.of(0)));
            assertThat(expireHistories()).hasSize(5);
        }
    }
}
