package com.loopers.domain.coupon.vo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class RebuildPlanTest {

    private static IssueRequestRow row(long userId, long sequence, String status, Integer slotNo) {
        return new IssueRequestRow(userId, sequence, status, slotNo, "ISSUED".equals(status) ? 900 + userId : null, false);
    }

    @DisplayName("행 상태대로 사용자 기록 · 대기열 · 예비를 만들고, 최대 순번을 카운터로 둔다. (D-174)")
    @Test
    void mapsRowsToRedisCopy() {
        RebuildPlan plan = RebuildPlan.of(List.of(
            row(7, 1, "ISSUED", 1),
            row(8, 2, "WAITING", 2),
            row(9, 3, "RESERVE", null)), true, false);

        assertAll(
            () -> assertThat(plan.users()).containsExactlyInAnyOrderEntriesOf(Map.of(
                7L, "ISSUED:1:907", 8L, "WAITING:2", 9L, "RESERVE:3")),
            () -> assertThat(plan.queue()).containsExactly(new RebuildPlan.Queued(8, 2, 2)),
            () -> assertThat(plan.reserve()).containsExactlyEntriesOf(Map.of(9L, 3L)),
            () -> assertThat(plan.maxSequence()).isEqualTo(3)
        );
    }

    @DisplayName("FAILED 가 남긴 슬롯에 주인이 없으면 가장 앞 예비가 다시 받는다. 승격 수에 더한다. (D-174)")
    @Test
    void refillsOrphanSlotWithFirstReserve() {
        RebuildPlan plan = RebuildPlan.of(List.of(
            row(7, 1, "ISSUED", 1),
            row(8, 2, "FAILED", 2),
            row(9, 3, "RESERVE", null),
            row(10, 4, "RESERVE", null)), true, false);

        assertAll(
            () -> assertThat(plan.users()).containsEntry(9L, "WAITING:3").containsEntry(10L, "RESERVE:4"),
            () -> assertThat(plan.queue()).containsExactly(new RebuildPlan.Queued(9, 3, 2)),
            () -> assertThat(plan.reserve()).containsExactlyEntriesOf(Map.of(10L, 4L)),
            () -> assertThat(plan.promotedCount()).isEqualTo(1)
        );
    }

    @DisplayName("이미 승격되어 발급된 사람이 슬롯을 갖고 있으면 다시 채우지 않는다.")
    @Test
    void doesNotRefillHeldSlot() {
        RebuildPlan plan = RebuildPlan.of(List.of(
            row(8, 2, "FAILED", 2),
            new IssueRequestRow(9, 3, "ISSUED", 2, 999L, true),
            row(10, 4, "RESERVE", null)), true, false);

        assertAll(
            () -> assertThat(plan.queue()).isEmpty(),
            () -> assertThat(plan.users()).containsEntry(10L, "RESERVE:4"),
            () -> assertThat(plan.promotedCount()).isEqualTo(1)
        );
    }

    @DisplayName("품절이면 다시 채우지 않는다. (D-124)")
    @Test
    void doesNotRefill_whenSoldOut() {
        RebuildPlan plan = RebuildPlan.of(List.of(
            row(8, 1, "FAILED", 1),
            row(9, 2, "RESERVE", null)), true, true);

        assertAll(
            () -> assertThat(plan.queue()).isEmpty(),
            () -> assertThat(plan.users()).containsEntry(9L, "RESERVE:2"),
            () -> assertThat(plan.soldOut()).isTrue()
        );
    }
}
