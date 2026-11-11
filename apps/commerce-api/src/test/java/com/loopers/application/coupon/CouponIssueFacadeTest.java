package com.loopers.application.coupon;

import com.loopers.application.time.ApplicationTime;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponIssueFacadeTest {

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00.123456Z");
    private static final Long CAMPAIGN_ID = 41L;
    private static final Long USER_ID = 73L;

    @Mock private CouponRepository coupons;
    @Mock private CouponCampaignRepository campaigns;
    @Mock private CouponIssuePermits permits;
    @Mock private CouponIssueTransaction transaction;

    private CouponIssueAdmissionState admission;
    private CouponIssueFacade facade;
    private CouponCampaign campaign;

    @BeforeEach
    void setUp() {
        admission = new CouponIssueAdmissionState();
        facade = new CouponIssueFacade(coupons, campaigns, permits, transaction, admission,
            new CouponIssueProperties(3, new CouponIssueProperties.Admission(false)),
            new ApplicationTime(Clock.fixed(NOW, ZoneOffset.UTC)));
        campaign = CouponCampaign.create("테스트 행사", 5, "5,000원 할인권",
            NOW.minusSeconds(60), NOW.plusSeconds(3600));
    }

    @DisplayName("신규 요청은 기존 결과와 캠페인을 확인한 뒤 permit, 발급 트랜잭션, token 반환 순서로 처리한다.")
    @Test
    void issuesAfterAdmissionAndReturnsOnlyItsPermit() {
        admission.open();
        CouponIssueInfo issued = new CouponIssueInfo(101L, CAMPAIGN_ID, NOW, "5,000원 할인권");
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(permits.tryAcquire(anyString(), eq(3))).thenReturn(true);
        when(transaction.issue(CAMPAIGN_ID, USER_ID)).thenReturn(issued);
        when(permits.release(anyString())).thenReturn(1L);

        CouponIssueResult result = facade.issue(CAMPAIGN_ID, USER_ID);

        assertThat(result.newlyIssued()).isTrue();
        assertThat(result.coupon()).isEqualTo(issued);
        InOrder order = inOrder(coupons, campaigns, permits, transaction);
        order.verify(coupons).findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID);
        order.verify(campaigns).findById(CAMPAIGN_ID);
        order.verify(permits).tryAcquire(anyString(), eq(3));
        order.verify(transaction).issue(CAMPAIGN_ID, USER_ID);
        order.verify(permits).release(anyString());
    }

    @DisplayName("이미 발급된 쿠폰은 캠페인 종료 뒤에도 기존 결과를 반환하고 새 permit을 쓰지 않는다.")
    @Test
    void returnsExistingCouponWithoutAdmission() {
        Coupon issued = Coupon.reconstitute(102L, CAMPAIGN_ID, USER_ID, NOW.minusSeconds(10));
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.of(issued));
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        CouponIssueResult result = facade.issue(CAMPAIGN_ID, USER_ID);

        assertThat(result.newlyIssued()).isFalse();
        assertThat(result.coupon().couponId()).isEqualTo(102L);
        assertThat(result.coupon().issuedAt()).isEqualTo(NOW.minusSeconds(10));
        verify(permits, never()).tryAcquire(anyString(), eq(3));
        verify(transaction, never()).issue(CAMPAIGN_ID, USER_ID);
    }

    @DisplayName("없는 캠페인은 permit 전에 404로 끝난다.")
    @Test
    void rejectsMissingCampaignBeforeAdmission() {
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facade.issue(CAMPAIGN_ID, USER_ID))
            .isInstanceOfSatisfying(CoreException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND));
        verify(permits, never()).tryAcquire(anyString(), eq(3));
        verify(transaction, never()).issue(CAMPAIGN_ID, USER_ID);
    }

    @DisplayName("기간이 지난 캠페인은 permit 전에 409로 끝난다.")
    @Test
    void rejectsExpiredCampaignBeforeAdmission() {
        CouponCampaign expired = CouponCampaign.create("지난 행사", 5, "5,000원 할인권",
            NOW.minusSeconds(3600), NOW.minusSeconds(1));
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> facade.issue(CAMPAIGN_ID, USER_ID))
            .isInstanceOfSatisfying(CoreException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT));
        verify(permits, never()).tryAcquire(anyString(), eq(3));
        verify(transaction, never()).issue(CAMPAIGN_ID, USER_ID);
    }

    @DisplayName("앱 수용 상태가 닫혀 있으면 Redis와 DB 신규 발급을 호출하지 않고 503을 반환한다.")
    @Test
    void rejectsWhenAdmissionIsClosed() {
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> facade.issue(CAMPAIGN_ID, USER_ID))
            .isInstanceOfSatisfying(CoreException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.SERVICE_UNAVAILABLE));
        verify(permits, never()).tryAcquire(anyString(), eq(3));
        verify(transaction, never()).issue(CAMPAIGN_ID, USER_ID);
    }

    @DisplayName("permit이 모두 사용 중이면 429로 거절하고 발급 트랜잭션에 진입하지 않는다.")
    @Test
    void rejectsWhenNoPermitIsAvailable() {
        admission.open();
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(permits.tryAcquire(anyString(), eq(3))).thenReturn(false);

        assertThatThrownBy(() -> facade.issue(CAMPAIGN_ID, USER_ID))
            .isInstanceOfSatisfying(CoreException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.TOO_MANY_REQUESTS));
        verify(transaction, never()).issue(CAMPAIGN_ID, USER_ID);
        verify(permits, never()).release(anyString());
    }

    @DisplayName("Redis 수용 상태를 확인할 수 없으면 503으로 응답하고 앱 수용을 닫는다.")
    @Test
    void closesAdmissionWhenPermitStoreIsUnavailable() {
        admission.open();
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(permits.tryAcquire(anyString(), eq(3)))
            .thenThrow(new CouponPermitStoreUnavailableException(new IllegalStateException("timeout")));

        assertThatThrownBy(() -> facade.issue(CAMPAIGN_ID, USER_ID))
            .isInstanceOfSatisfying(CoreException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.SERVICE_UNAVAILABLE));
        assertThat(admission.isOpen()).isFalse();
        verify(transaction, never()).issue(CAMPAIGN_ID, USER_ID);
    }

    @DisplayName("Lua 입력·키 오류는 500으로 응답하고 앱 수용을 닫는다.")
    @Test
    void closesAdmissionWhenPermitScriptIsInvalid() {
        admission.open();
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(permits.tryAcquire(anyString(), eq(3)))
            .thenThrow(new CouponPermitScriptException(new IllegalStateException("WRONGTYPE")));

        assertThatThrownBy(() -> facade.issue(CAMPAIGN_ID, USER_ID))
            .isInstanceOfSatisfying(CoreException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.INTERNAL_ERROR));
        assertThat(admission.isOpen()).isFalse();
        verify(transaction, never()).issue(CAMPAIGN_ID, USER_ID);
    }

    @DisplayName("permit 반환 결과가 0이어도 commit된 발급 결과를 유지하고 신규 수용을 닫는다.")
    @Test
    void keepsCommittedResultAndClosesAdmissionWhenPermitWasMissing() {
        admission.open();
        CouponIssueInfo issued = new CouponIssueInfo(103L, CAMPAIGN_ID, NOW, "5,000원 할인권");
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(permits.tryAcquire(anyString(), eq(3))).thenReturn(true);
        when(transaction.issue(CAMPAIGN_ID, USER_ID)).thenReturn(issued);
        when(permits.release(anyString())).thenReturn(0L);

        CouponIssueResult result = facade.issue(CAMPAIGN_ID, USER_ID);

        assertThat(result.newlyIssued()).isTrue();
        assertThat(result.coupon()).isEqualTo(issued);
        assertThat(admission.isOpen()).isFalse();
    }

    @DisplayName("DB 오류 뒤에도 rollback 종료 후 자기 permit을 반환한다.")
    @Test
    void returnsPermitAfterTransactionFailure() {
        admission.open();
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(permits.tryAcquire(anyString(), eq(3))).thenReturn(true);
        when(permits.release(anyString())).thenReturn(1L);
        when(transaction.issue(CAMPAIGN_ID, USER_ID)).thenThrow(new IllegalStateException("DB failed"));

        assertThatThrownBy(() -> facade.issue(CAMPAIGN_ID, USER_ID))
            .isInstanceOf(IllegalStateException.class);
        verify(permits).release(anyString());
    }

    @DisplayName("동시 중복으로 발급 트랜잭션이 rollback되면 새 조회로 기존 couponId를 반환한다.")
    @Test
    void readsExistingCouponAfterConcurrentDuplicateRollback() {
        admission.open();
        Coupon existing = Coupon.reconstitute(105L, CAMPAIGN_ID, USER_ID, NOW.minusSeconds(2));
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID))
            .thenReturn(Optional.empty(), Optional.of(existing));
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(permits.tryAcquire(anyString(), eq(3))).thenReturn(true);
        when(permits.release(anyString())).thenReturn(1L);
        when(transaction.issue(CAMPAIGN_ID, USER_ID)).thenThrow(new com.loopers.domain.coupon.DuplicateCouponIssueException(null));

        CouponIssueResult result = facade.issue(CAMPAIGN_ID, USER_ID);

        assertThat(result.newlyIssued()).isFalse();
        assertThat(result.coupon().couponId()).isEqualTo(105L);
        verify(permits).release(anyString());
    }

    @DisplayName("보유 목록은 요청자 소유 쿠폰만 저장소의 안정된 정렬대로 반환한다.")
    @Test
    void listsOnlyRequestingUsersCoupons() {
        Coupon coupon = Coupon.reconstitute(104L, CAMPAIGN_ID, USER_ID, NOW.minusSeconds(1));
        when(coupons.findAllIssuedByUserId(USER_ID)).thenReturn(List.of(coupon));
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        List<CouponIssueInfo> result = facade.getMyCoupons(USER_ID);

        assertThat(result).containsExactly(new CouponIssueInfo(104L, CAMPAIGN_ID, NOW.minusSeconds(1), "5,000원 할인권"));
        verify(coupons).findAllIssuedByUserId(USER_ID);
    }
}
