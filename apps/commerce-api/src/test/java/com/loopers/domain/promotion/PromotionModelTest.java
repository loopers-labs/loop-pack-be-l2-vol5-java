package com.loopers.domain.promotion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZonedDateTime;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 범용 프로모션 메타데이터 (promotion). */
class PromotionModelTest {

    private static final ZonedDateTime STARTS_AT = ZonedDateTime.parse("2026-11-10T10:00:00+09:00[Asia/Seoul]");
    private static final ZonedDateTime ENDS_AT = STARTS_AT.plusDays(7);

    private static PromotionModel ready() {
        return PromotionModel.create("11월 선착순 쿠폰", PromotionType.COUPON_ISSUE, STARTS_AT, ENDS_AT);
    }

    private static PromotionModel inStatus(PromotionStatus status) {
        PromotionModel promotion = ready();
        switch (status) {
            case READY -> { }
            case ACTIVE -> promotion.activate();
            case PAUSED -> {
                promotion.activate();
                promotion.pause();
            }
            case ENDED -> promotion.end();
        }
        return promotion;
    }

    @DisplayName("새 프로모션은 READY 이고, 이름·종류·기간은 받은 그대로다.")
    @Test
    void create_startsAsReady() {
        PromotionModel promotion = ready();

        assertThat(promotion.getStatus()).isEqualTo(PromotionStatus.READY);
        assertThat(promotion.getName()).isEqualTo("11월 선착순 쿠폰");
        assertThat(promotion.getType()).isEqualTo(PromotionType.COUPON_ISSUE);
        assertThat(promotion.getStartsAt()).isEqualTo(STARTS_AT);
        assertThat(promotion.getEndsAt()).isEqualTo(ENDS_AT);
        assertThat(promotion.isActive()).isFalse();
    }

    @DisplayName("이름이 비어 있으면 만들지 않는다.")
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void create_rejectsBlankName(String blank) {
        assertThatThrownBy(() -> PromotionModel.create(blank, PromotionType.COUPON_ISSUE, STARTS_AT, ENDS_AT))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("이름은 100자까지 받고, 101자면 만들지 않는다.")
    @Test
    void create_rejectsTooLongName() {
        assertThat(PromotionModel.create("a".repeat(100), PromotionType.COUPON_ISSUE, STARTS_AT, ENDS_AT).getName()).hasSize(100);
        assertThatThrownBy(() -> PromotionModel.create("a".repeat(101), PromotionType.COUPON_ISSUE, STARTS_AT, ENDS_AT))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("종류·시작 시각·종료 시각이 없으면 만들지 않는다.")
    @Test
    void create_rejectsMissingTypeOrPeriod() {
        assertThatThrownBy(() -> PromotionModel.create("p", null, STARTS_AT, ENDS_AT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PromotionModel.create("p", PromotionType.COUPON_ISSUE, null, ENDS_AT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PromotionModel.create("p", PromotionType.COUPON_ISSUE, STARTS_AT, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("시작 시각이 종료 시각과 같거나 뒤면 만들지 않는다.")
    @Test
    void create_rejectsStartNotBeforeEnd() {
        assertThatThrownBy(() -> PromotionModel.create("p", PromotionType.COUPON_ISSUE, STARTS_AT, STARTS_AT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PromotionModel.create("p", PromotionType.COUPON_ISSUE, ENDS_AT, STARTS_AT))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("READY 를 열면 ACTIVE 이고 참여할 수 있다.")
    @Test
    void activate_fromReady() {
        PromotionModel promotion = ready();

        promotion.activate();

        assertThat(promotion.getStatus()).isEqualTo(PromotionStatus.ACTIVE);
        assertThat(promotion.isActive()).isTrue();
    }

    @DisplayName("ACTIVE 를 멈추면 PAUSED 이고 참여할 수 없다. 재개하면 다시 ACTIVE 다.")
    @Test
    void pause_thenResume() {
        PromotionModel promotion = inStatus(PromotionStatus.ACTIVE);

        promotion.pause();
        assertThat(promotion.getStatus()).isEqualTo(PromotionStatus.PAUSED);
        assertThat(promotion.isActive()).isFalse();

        promotion.resume();
        assertThat(promotion.getStatus()).isEqualTo(PromotionStatus.ACTIVE);
        assertThat(promotion.isActive()).isTrue();
    }

    @DisplayName("READY·ACTIVE·PAUSED 는 끝낼 수 있고, 끝나면 참여할 수 없다.")
    @ParameterizedTest
    @ValueSource(strings = {"READY", "ACTIVE", "PAUSED"})
    void end_fromNonEnded(PromotionStatus from) {
        PromotionModel promotion = inStatus(from);

        promotion.end();

        assertThat(promotion.getStatus()).isEqualTo(PromotionStatus.ENDED);
        assertThat(promotion.isActive()).isFalse();
    }

    @DisplayName("ENDED 에서는 어떤 전이도 할 수 없고 상태가 그대로다.")
    @Test
    void ended_rejectsAllTransitions() {
        assertRejected(PromotionStatus.ENDED, PromotionModel::activate);
        assertRejected(PromotionStatus.ENDED, PromotionModel::pause);
        assertRejected(PromotionStatus.ENDED, PromotionModel::resume);
        assertRejected(PromotionStatus.ENDED, PromotionModel::end);
    }

    @DisplayName("허용되지 않은 전이는 거절되고 상태가 그대로다.")
    @Test
    void rejectsDisallowedTransitions() {
        assertRejected(PromotionStatus.READY, PromotionModel::pause);
        assertRejected(PromotionStatus.READY, PromotionModel::resume);
        assertRejected(PromotionStatus.ACTIVE, PromotionModel::activate);
        assertRejected(PromotionStatus.ACTIVE, PromotionModel::resume);
        assertRejected(PromotionStatus.PAUSED, PromotionModel::activate);
        assertRejected(PromotionStatus.PAUSED, PromotionModel::pause);
    }

    private static void assertRejected(PromotionStatus from, Consumer<PromotionModel> transition) {
        PromotionModel promotion = inStatus(from);

        assertThatThrownBy(() -> transition.accept(promotion))
            .isInstanceOf(IllegalStateException.class);
        assertThat(promotion.getStatus()).isEqualTo(from);
    }
}
