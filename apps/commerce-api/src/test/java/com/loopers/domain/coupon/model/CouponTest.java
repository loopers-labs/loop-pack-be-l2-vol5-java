package com.loopers.domain.coupon.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CouponTest {
    private static final Instant START = Instant.parse("2026-11-13T01:00:00Z");
    private static final Instant END = Instant.parse("2026-11-13T02:00:00Z");

    @Test
    @DisplayName("발급한 DB 슬롯은 다시 발급할 수 없고 상태를 유지한다")
    void preventsSlotReuse() {
        // arrange
        CouponSlot slot = CouponSlot.restore(10L, 1L, CouponSlotState.AVAILABLE);

        // act
        slot.markIssued();

        // assert
        assertThatThrownBy(slot::markIssued)
                .isInstanceOf(DomainException.class).extracting("errorCode")
                .isEqualTo(DomainErrorCode.COUPON_SLOT_ALREADY_ISSUED);
        assertThat(slot.getState()).isEqualTo(CouponSlotState.ISSUED);
    }

    @Test
    @DisplayName("행사를 닫아도 발급 쿠폰의 혜택·발급 시각·누적 수량은 보존한다")
    void preservesIssuedCouponSnapshot() {
        // arrange
        CouponBenefit benefit = new CouponBenefit("5,000원 할인권", 5_000L);
        CouponCampaign campaign = CouponCampaign.restore(1L, "행사", true, 3, 0, benefit, START, END);
        CouponSlot slot = CouponSlot.restore(10L, 1L, CouponSlotState.AVAILABLE);
        Instant dbNow = START.plusNanos(123_456_789);

        // act: 실제 구현에서는 아래 변경과 INSERT를 같은 DB TX로 저장한다.
        slot.markIssued();
        Coupon coupon = Coupon.create(1L, 10L, 7L, benefit, dbNow);
        campaign.recordIssue(dbNow);
        campaign.deactivate();

        // assert
        assertThat(coupon.getBenefit()).isEqualTo(new CouponBenefit("5,000원 할인권", 5_000L));
        assertThat(coupon.getIssuedAt()).isEqualTo(dbNow.truncatedTo(ChronoUnit.MICROS));
        assertThat(coupon.getUserId()).isEqualTo(7L);
        assertThat(slot.getState()).isEqualTo(CouponSlotState.ISSUED);
        assertThat(campaign.getIssuedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("잘못된 소유자·슬롯과 시각 없는 쿠폰 복원을 거절한다")
    void rejectsInvalidPersistedIdentity() {
        // arrange
        CouponBenefit benefit = new CouponBenefit("할인권", 1L);

        // act / assert
        assertThatThrownBy(() -> Coupon.create(1L, 10L, 0L, benefit, START))
                .isInstanceOf(DomainException.class).extracting("errorCode")
                .isEqualTo(DomainErrorCode.INVALID_USER_ID);
        assertThatThrownBy(() -> Coupon.restore(1L, 1L, 0L, 7L, benefit, START))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Coupon.restore(1L, 1L, 10L, 7L, benefit, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
