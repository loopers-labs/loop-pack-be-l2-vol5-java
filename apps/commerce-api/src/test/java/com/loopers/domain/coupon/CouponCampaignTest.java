package com.loopers.domain.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponCampaignTest {

    private static final Instant START_AT = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant END_AT = Instant.parse("2026-12-01T00:00:00Z");
    private static final Instant ISSUE_NOW = START_AT.plusSeconds(60);

    @DisplayName("캠페인은 발급 전 수량 0과 미종료 상태로 생성한다.")
    @Test
    void createsCampaignBeforeAnyIssuance() {
        CouponCampaign campaign = createCampaign(100);

        assertThat(campaign.getId()).isNull();
        assertThat(campaign.getName()).isEqualTo("쿠폰 행사");
        assertThat(campaign.getTotalQuota()).isEqualTo(100);
        assertThat(campaign.getIssuedCount()).isZero();
        assertThat(campaign.getBenefitDescription()).isEqualTo("5,000원 할인권");
        assertThat(campaign.getStartAt()).isEqualTo(START_AT);
        assertThat(campaign.getEndAt()).isEqualTo(END_AT);
        assertThat(campaign.getTerminatedAt()).isNull();
    }

    @DisplayName("이름이 비어 있으면 캠페인을 생성하지 않는다.")
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingName(String name) {
        assertThatThrownBy(() -> CouponCampaign.create(name, 100, "5,000원 할인권", START_AT, END_AT))
            .isInstanceOfSatisfying(CoreException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
    }

    @DisplayName("혜택 설명이 비어 있으면 캠페인을 생성하지 않는다.")
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsMissingBenefitDescription(String benefitDescription) {
        assertThatThrownBy(() -> CouponCampaign.create("쿠폰 행사", 100, benefitDescription, START_AT, END_AT))
            .isInstanceOfSatisfying(CoreException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
    }

    @DisplayName("발급 총량은 양수여야 한다.")
    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveQuota(long totalQuota) {
        assertThatThrownBy(() -> createCampaign(totalQuota))
            .isInstanceOfSatisfying(CoreException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
    }

    @DisplayName("발급 기간의 시작은 종료보다 빨라야 하고 두 시각은 필수다.")
    @Test
    void rejectsInvalidPeriod() {
        assertInvalidPeriod(START_AT, START_AT);
        assertInvalidPeriod(END_AT, START_AT);
        assertInvalidPeriod(null, END_AT);
        assertInvalidPeriod(START_AT, null);
    }

    @DisplayName("발급 시작 시각은 포함하고 종료 시각은 제외한다.")
    @Test
    void usesInclusiveStartAndExclusiveEnd() {
        CouponCampaign campaign = createCampaign(100);

        assertThatCode(() -> campaign.validateIssuable(START_AT)).doesNotThrowAnyException();
        assertThatCode(() -> campaign.validateIssuable(END_AT.minusNanos(1))).doesNotThrowAnyException();
        assertUnavailable(campaign, START_AT.minusNanos(1));
        assertUnavailable(campaign, END_AT);
        assertUnavailable(campaign, END_AT.plusSeconds(1));
        assertThat(campaign.getIssuedCount()).isZero();
        assertThat(campaign.getTerminatedAt()).isNull();
    }

    @DisplayName("새 발급 한 건은 발급 수를 한 번 증가시키고 총량 소진 전에는 조기 종료하지 않는다.")
    @Test
    void recordsOneNewIssue() {
        CouponCampaign campaign = createCampaign(100);

        campaign.recordIssue(ISSUE_NOW);

        assertThat(campaign.getIssuedCount()).isEqualTo(1);
        assertThat(campaign.getTerminatedAt()).isNull();
    }

    @DisplayName("마지막 한 장을 발급하면 같은 발급 시각으로 조기 종료하고 추가 발급을 거절한다.")
    @Test
    void terminatesOnLastIssueAndPreservesTerminalState() {
        CouponCampaign campaign = createCampaign(1);

        campaign.recordIssue(ISSUE_NOW);

        assertThat(campaign.getIssuedCount()).isEqualTo(1);
        assertThat(campaign.getTerminatedAt()).isEqualTo(ISSUE_NOW);
        assertThatThrownBy(() -> campaign.recordIssue(ISSUE_NOW.plusSeconds(1)))
            .isInstanceOfSatisfying(CoreException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.CONFLICT));
        assertThat(campaign.getIssuedCount()).isEqualTo(1);
        assertThat(campaign.getTerminatedAt()).isEqualTo(ISSUE_NOW);
    }

    @DisplayName("기간 밖 발급은 수량과 조기 종료 시각을 변경하지 않는다.")
    @Test
    void preservesStateWhenIssueIsOutsidePeriod() {
        CouponCampaign campaign = createCampaign(1);

        assertThatThrownBy(() -> campaign.recordIssue(END_AT))
            .isInstanceOfSatisfying(CoreException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.CONFLICT));
        assertThat(campaign.getIssuedCount()).isZero();
        assertThat(campaign.getTerminatedAt()).isNull();
    }

    @DisplayName("저장된 발급 수가 총량을 소진했다면 조기 종료 시각이 없어도 신규 발급을 거절한다.")
    @Test
    void rejectsSoldOutCampaignWhenRestored() {
        CouponCampaign campaign = CouponCampaign.reconstitute(
            1L, "쿠폰 행사", 100, 100, "5,000원 할인권", START_AT, END_AT, null
        );

        assertThat(campaign.getId()).isEqualTo(1L);
        assertUnavailable(campaign, ISSUE_NOW);
    }

    @DisplayName("저장된 발급 수는 0 이상 총량 이하여야 한다.")
    @ParameterizedTest
    @ValueSource(longs = {-1, 101})
    void rejectsInconsistentIssuedCount(long issuedCount) {
        assertThatThrownBy(() -> CouponCampaign.reconstitute(
            1L, "쿠폰 행사", 100, issuedCount, "5,000원 할인권", START_AT, END_AT, null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("조기 품절로 종료된 캠페인은 저장된 종료 시각과 함께 복원한다.")
    @Test
    void restoresEarlySoldOutCampaign() {
        CouponCampaign campaign = CouponCampaign.reconstitute(
            1L, "쿠폰 행사", 100, 100, "5,000원 할인권", START_AT, END_AT, ISSUE_NOW
        );

        assertThat(campaign.getTerminatedAt()).isEqualTo(ISSUE_NOW);
        assertUnavailable(campaign, ISSUE_NOW.plusSeconds(1));
    }

    @DisplayName("발급 수가 남은 캠페인에 조기 품절 종료 시각을 저장할 수 없다.")
    @Test
    void rejectsTerminationBeforeQuotaIsExhausted() {
        assertThatThrownBy(() -> CouponCampaign.reconstitute(
            1L, "쿠폰 행사", 100, 99, "5,000원 할인권", START_AT, END_AT, ISSUE_NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("조기 품절 시각은 발급 기간 안이어야 한다.")
    @Test
    void rejectsTerminationOutsideIssuancePeriod() {
        assertThatThrownBy(() -> CouponCampaign.reconstitute(
            1L, "쿠폰 행사", 100, 100, "5,000원 할인권", START_AT, END_AT, START_AT.minusNanos(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CouponCampaign.reconstitute(
            1L, "쿠폰 행사", 100, 100, "5,000원 할인권", START_AT, END_AT, END_AT
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static CouponCampaign createCampaign(long totalQuota) {
        return CouponCampaign.create("쿠폰 행사", totalQuota, "5,000원 할인권", START_AT, END_AT);
    }

    private static void assertInvalidPeriod(Instant startAt, Instant endAt) {
        assertThatThrownBy(() -> CouponCampaign.create("쿠폰 행사", 100, "5,000원 할인권", startAt, endAt))
            .isInstanceOfSatisfying(CoreException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST));
    }

    private static void assertUnavailable(CouponCampaign campaign, Instant issueNow) {
        assertThatThrownBy(() -> campaign.validateIssuable(issueNow))
            .isInstanceOfSatisfying(CoreException.class,
                error -> assertThat(error.getErrorType()).isEqualTo(ErrorType.CONFLICT));
    }
}
