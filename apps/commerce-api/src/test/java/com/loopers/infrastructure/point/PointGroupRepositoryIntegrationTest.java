package com.loopers.infrastructure.point;

import com.loopers.domain.common.Money;
import com.loopers.domain.point.PointGroup;
import com.loopers.domain.point.PointGroupRepository;
import com.loopers.domain.point.PointHistory;
import com.loopers.domain.point.PointHistoryRepository;
import com.loopers.domain.point.PointUsage;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PointGroupRepositoryIntegrationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Period VALIDITY = Period.ofYears(5);

    @Autowired
    private PointGroupRepository pointGroupRepository;

    @Autowired
    private PointHistoryRepository pointHistoryRepository;

    @Autowired
    private PointHistoryJpaRepository pointHistoryJpaRepository;

    @Autowired
    private EntityManager entityManager;

    private PointGroup chargedAt(Long userId, long amount, ZonedDateTime chargedAt) {
        return pointGroupRepository.save(PointGroup.charge(userId, Money.of(amount), chargedAt, VALIDITY));
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @DisplayName("저장한 그룹을 다시 읽으면 충전액·남은 금액(Money)과 만료 시각이 그대로다.")
    @Test
    void savesAndReloadsGroup() {
        // arrange
        ZonedDateTime chargedAt = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, SEOUL);
        PointGroup saved = chargedAt(1L, 10_000, chargedAt);
        flushAndClear();

        // act
        List<PointGroup> found = pointGroupRepository.findRemainingByUserId(1L);

        // assert
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getId()).isEqualTo(saved.getId());
        assertThat(found.get(0).getAmount()).isEqualTo(Money.of(10_000));
        assertThat(found.get(0).getRemaining()).isEqualTo(Money.of(10_000));
        assertThat(found.get(0).getExpiresAt().toInstant()).isEqualTo(chargedAt.plus(VALIDITY).toInstant());
    }

    @DisplayName("PNT-05 남은 금액이 있는 내 그룹만, 만료 시각이 이른 순으로 돌려준다.")
    @Test
    void findsRemainingGroupsOrderedByExpiry() {
        // arrange
        PointGroup later = chargedAt(1L, 5_000, ZonedDateTime.of(2026, 3, 1, 0, 0, 0, 0, SEOUL));
        PointGroup earlier = chargedAt(1L, 5_000, ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, SEOUL));
        PointGroup emptied = chargedAt(1L, 3_000, ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, SEOUL));
        emptied.use(Money.of(3_000), ZonedDateTime.of(2025, 6, 1, 0, 0, 0, 0, SEOUL));
        pointGroupRepository.save(emptied);
        chargedAt(2L, 9_000, ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, SEOUL));
        flushAndClear();

        // act
        List<PointGroup> found = pointGroupRepository.findRemainingByUserId(1L);

        // assert
        assertThat(found).extracting(PointGroup::getId).containsExactly(earlier.getId(), later.getId());
    }

    @DisplayName("ADR-04 한 그룹의 이력 합은 그 그룹의 남은 금액과 같다 (충전 +10,000, 사용 -7,000 → 남은 3,000).")
    @Test
    void historySumEqualsRemaining() {
        // arrange
        ZonedDateTime chargedAt = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, SEOUL);
        PointGroup group = chargedAt(1L, 10_000, chargedAt);
        pointHistoryRepository.save(PointHistory.charge(group, chargedAt));
        group.use(Money.of(7_000), chargedAt.plusDays(1));
        pointGroupRepository.save(group);
        pointHistoryRepository.save(PointHistory.use(new PointUsage(group, Money.of(7_000)), 99L, chargedAt.plusDays(1)));
        flushAndClear();

        // act
        long historySum = pointHistoryJpaRepository.findAll().stream()
            .filter(history -> history.getGroupId().equals(group.getId()))
            .mapToLong(PointHistory::getAmount)
            .sum();
        PointGroup reloaded = pointGroupRepository.findRemainingByUserId(1L).get(0);

        // assert
        assertThat(historySum).isEqualTo(3_000L);
        assertThat(reloaded.getRemaining()).isEqualTo(Money.of(3_000));
    }
}
