package com.loopers.domain.coupon.entity;

import com.loopers.domain.coupon.enums.IssueRequestStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

class CouponIssueRequestTest {

    @DisplayName("순번이 한도 안이면 WAITING 이고 순번을 슬롯으로 쓴다. (D-121, D-141)")
    @Test
    void waitsWithSequenceAsSlot_whenWithinQuota() {
        CouponIssueRequest request = CouponIssueRequest.accepted(1L, 7L, 5, 5);

        assertAll(
            () -> assertThat(request.getStatus()).isEqualTo(IssueRequestStatus.WAITING),
            () -> assertThat(request.getSlotNo()).isEqualTo(5)
        );
    }

    @DisplayName("순번이 한도 밖이면 RESERVE 이고 슬롯이 없다. (D-121)")
    @Test
    void reservesWithoutSlot_whenBeyondQuota() {
        CouponIssueRequest request = CouponIssueRequest.accepted(1L, 7L, 6, 5);

        assertAll(
            () -> assertThat(request.getStatus()).isEqualTo(IssueRequestStatus.RESERVE),
            () -> assertThat(request.getSlotNo()).isNull()
        );
    }

    @DisplayName("순번이 1 보다 작으면, 예외가 발생한다.")
    @Test
    void rejectsSequenceBelowOne() {
        assertThatThrownBy(() -> CouponIssueRequest.accepted(1L, 7L, 0, 5))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
