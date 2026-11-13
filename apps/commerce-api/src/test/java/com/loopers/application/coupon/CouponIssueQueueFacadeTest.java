package com.loopers.application.coupon;

import com.loopers.application.coupon.CouponIssueQueueResult.Accepted;
import com.loopers.application.coupon.CouponIssueQueueResult.Completed;
import com.loopers.application.coupon.CouponIssueQueueResult.CouponAlreadyIssued;
import com.loopers.application.time.ApplicationTime;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponIssueRequest;
import com.loopers.domain.coupon.CouponIssueRequestReason;
import com.loopers.domain.coupon.CouponIssueRequestStatus;
import com.loopers.domain.coupon.CouponNotIssuableException;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponIssueQueueFacadeTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final Long CAMPAIGN_ID = 41L;
    private static final Long USER_ID = 73L;
    private static final String REQUEST_ID = "8e078261-f7c6-4d41-a8e9-f068d900c0cf";
    private static final String TOKEN = "previous-execution-token";
    private static final int CAPACITY = 20;

    @Mock private CouponIssueQueue queue;
    @Mock private CouponRepository coupons;
    @Mock private CouponCampaignRepository campaigns;

    private CouponIssueAdmissionState admission;
    private CouponIssueQueueFacade facade;
    private CouponCampaign campaign;

    @BeforeEach
    void setUp() {
        admission = new CouponIssueAdmissionState();
        admission.open();
        facade = new CouponIssueQueueFacade(queue, coupons, campaigns, admission,
            new CouponIssueQueueProperties(CAPACITY, null), new ApplicationTime(Clock.fixed(NOW, ZoneOffset.UTC)));
        campaign = CouponCampaign.create("테스트 행사", 5, "5,000원 할인권", NOW.minusSeconds(60), NOW.plusSeconds(3600));
    }

    @DisplayName("작업도 쿠폰도 없으면 대기 작업을 새로 만들어 접수한다.")
    @Test
    void acceptsNewRequest() {
        whenNoRequestAndNoCoupon();
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(queue.submit(any(), eq(CAPACITY))).thenAnswer(invocation ->
            new CouponIssueQueueSubmission.Created(invocation.getArgument(0)));

        CouponIssueQueueResult result = facade.submit(CAMPAIGN_ID, USER_ID);

        assertThat(result).isInstanceOfSatisfying(Accepted.class, accepted -> {
            assertThat(accepted.request().getCampaignId()).isEqualTo(CAMPAIGN_ID);
            assertThat(accepted.request().getUserId()).isEqualTo(USER_ID);
            assertThat(accepted.request().getStatus()).isEqualTo(CouponIssueRequestStatus.WAITING);
            assertThat(accepted.request().getRequestedAt()).isEqualTo(NOW);
        });
    }

    @DisplayName("진행 중인 기존 작업은 수용이 닫혀 있어도 그 작업을 접수 상태로 돌려준다.")
    @ParameterizedTest
    @EnumSource(value = CouponIssueRequestStatus.class, names = {"WAITING", "PROCESSING", "UNKNOWN"})
    void returnsUnfinishedRequestAsAccepted(CouponIssueRequestStatus status) {
        admission.close();
        CouponIssueRequest existing = switch (status) {
            case WAITING -> stored(status, null, null, null);
            case PROCESSING -> stored(status, null, null, TOKEN);
            default -> stored(status, null, CouponIssueRequestReason.DB_RESULT_UNKNOWN, TOKEN);
        };
        when(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.of(existing));

        assertThat(facade.submit(CAMPAIGN_ID, USER_ID)).isEqualTo(new Accepted(existing));
        verifyNoInteractions(coupons, campaigns);
        verify(queue, never()).submit(any(), anyInt());
    }

    @DisplayName("성공이나 업무 거절로 끝난 기존 작업은 저장된 결과를 돌려준다.")
    @Test
    void returnsCompletedRequestAsCompleted() {
        CouponIssueRequest succeeded = stored(CouponIssueRequestStatus.SUCCEEDED, 105L, null, TOKEN);
        when(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.of(succeeded));

        assertThat(facade.submit(CAMPAIGN_ID, USER_ID)).isEqualTo(new Completed(succeeded));

        CouponIssueRequest soldOut = stored(CouponIssueRequestStatus.FAILED, null, CouponIssueRequestReason.SOLD_OUT, TOKEN);
        when(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.of(soldOut));

        assertThat(facade.submit(CAMPAIGN_ID, USER_ID)).isEqualTo(new Completed(soldOut));
        verifyNoInteractions(coupons, campaigns);
    }

    @DisplayName("시스템 실패 작업은 쿠폰이 없고 발급할 수 있으면 같은 작업 ID로 다시 대기시킨다.")
    @Test
    void requeuesSystemFailure() {
        CouponIssueRequest failed = stored(CouponIssueRequestStatus.FAILED, null, CouponIssueRequestReason.DB_ERROR, TOKEN);
        CouponIssueRequest requeued = stored(CouponIssueRequestStatus.WAITING, null, null, null);
        when(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.of(failed));
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(queue.requeueFailed(REQUEST_ID, CouponIssueRequestReason.DB_ERROR, CAPACITY))
            .thenReturn(new CouponIssueQueueResubmission.Requeued(requeued));

        assertThat(facade.submit(CAMPAIGN_ID, USER_ID)).isEqualTo(new Accepted(requeued));
    }

    @DisplayName("시스템 실패 작업을 재접수하는 중 쿠폰을 확인하면 같은 작업을 성공으로 바꾼다.")
    @Test
    void marksSystemFailureSucceededWhenCouponExists() {
        CouponIssueRequest failed = stored(CouponIssueRequestStatus.FAILED, null, CouponIssueRequestReason.DB_ERROR, TOKEN);
        CouponIssueRequest succeeded = stored(CouponIssueRequestStatus.SUCCEEDED, 105L, null, TOKEN);
        when(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.of(failed));
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.of(coupon()));
        when(queue.markFailedSucceeded(REQUEST_ID, CouponIssueRequestReason.DB_ERROR, 105L))
            .thenReturn(new CouponIssueQueueResubmission.Succeeded(succeeded));

        assertThat(facade.submit(CAMPAIGN_ID, USER_ID)).isEqualTo(new Completed(succeeded));
        verify(queue, never()).requeueFailed(any(), any(), anyInt());
        verifyNoInteractions(campaigns);
    }

    @DisplayName("시스템 실패 작업의 캠페인이 발급 불가면 다시 대기시키지 않고 409로 거절한다.")
    @Test
    void rejectsSystemFailureResubmissionWhenCampaignIsNotIssuable() {
        CouponIssueRequest failed = stored(CouponIssueRequestStatus.FAILED, null, CouponIssueRequestReason.INTERNAL_ERROR,
            TOKEN);
        CouponCampaign soldOut = CouponCampaign.reconstitute(CAMPAIGN_ID, "테스트 행사", 5, 5, "5,000원 할인권",
            NOW.minusSeconds(60), NOW.plusSeconds(3600), NOW.minusSeconds(1));
        when(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.of(failed));
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(soldOut));

        assertThatThrownBy(() -> facade.submit(CAMPAIGN_ID, USER_ID)).isInstanceOf(CouponNotIssuableException.class);
        verify(queue, never()).requeueFailed(any(), any(), anyInt());
    }

    @DisplayName("시스템 실패 작업을 다시 대기시킬 때 한도가 차 있으면 429로 거절한다.")
    @Test
    void rejectsSystemFailureResubmissionWhenFull() {
        CouponIssueRequest failed = stored(CouponIssueRequestStatus.FAILED, null, CouponIssueRequestReason.DB_ERROR, TOKEN);
        when(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.of(failed));
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(queue.requeueFailed(REQUEST_ID, CouponIssueRequestReason.DB_ERROR, CAPACITY))
            .thenReturn(new CouponIssueQueueResubmission.Full(failed));

        assertErrorType(() -> facade.submit(CAMPAIGN_ID, USER_ID), ErrorType.TOO_MANY_REQUESTS);
    }

    @DisplayName("시스템 실패 재접수 중 그사이 쿠폰이 발급되고 캠페인이 품절되면, 409 대신 같은 작업을 성공으로 바꾼다.")
    @Test
    void marksSystemFailureSucceededWhenCouponIsIssuedBeforeCampaignRejection() {
        CouponIssueRequest failed = stored(CouponIssueRequestStatus.FAILED, null, CouponIssueRequestReason.DB_ERROR, TOKEN);
        CouponIssueRequest succeeded = stored(CouponIssueRequestStatus.SUCCEEDED, 105L, null, TOKEN);
        when(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.of(failed));
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID))
            .thenReturn(Optional.empty(), Optional.of(coupon()));
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(soldOutCampaign()));
        when(queue.markFailedSucceeded(REQUEST_ID, CouponIssueRequestReason.DB_ERROR, 105L))
            .thenReturn(new CouponIssueQueueResubmission.Succeeded(succeeded));

        assertThat(facade.submit(CAMPAIGN_ID, USER_ID)).isEqualTo(new Completed(succeeded));
        verify(queue, never()).requeueFailed(any(), any(), anyInt());
    }

    @DisplayName("캠페인 거절 전에 다른 요청이 같은 작업을 다시 대기시키면 현재 작업을 돌려준다.")
    @Test
    void returnsCurrentRequestWhenCampaignRejectsAfterConcurrentResubmission() {
        CouponIssueRequest failed = stored(CouponIssueRequestStatus.FAILED, null, CouponIssueRequestReason.DB_ERROR, TOKEN);
        CouponIssueRequest waiting = stored(CouponIssueRequestStatus.WAITING, null, null, null);
        when(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.of(failed), Optional.of(waiting));
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID))
            .thenReturn(Optional.empty(), Optional.empty());
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(soldOutCampaign()));

        assertThat(facade.submit(CAMPAIGN_ID, USER_ID)).isEqualTo(new Accepted(waiting));
        verify(queue, never()).requeueFailed(any(), any(), anyInt());
    }

    @DisplayName("시스템 실패 재접수 중 그사이 쿠폰이 발급되고 대기열이 가득 차면, 429 대신 같은 작업을 성공으로 바꾼다.")
    @Test
    void marksSystemFailureSucceededWhenCouponIsIssuedBeforeFullRejection() {
        CouponIssueRequest failed = stored(CouponIssueRequestStatus.FAILED, null, CouponIssueRequestReason.DB_ERROR, TOKEN);
        CouponIssueRequest succeeded = stored(CouponIssueRequestStatus.SUCCEEDED, 105L, null, TOKEN);
        when(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.of(failed));
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID))
            .thenReturn(Optional.empty(), Optional.of(coupon()));
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(queue.requeueFailed(REQUEST_ID, CouponIssueRequestReason.DB_ERROR, CAPACITY))
            .thenReturn(new CouponIssueQueueResubmission.Full(failed));
        when(queue.markFailedSucceeded(REQUEST_ID, CouponIssueRequestReason.DB_ERROR, 105L))
            .thenReturn(new CouponIssueQueueResubmission.Succeeded(succeeded));

        assertThat(facade.submit(CAMPAIGN_ID, USER_ID)).isEqualTo(new Completed(succeeded));
    }

    @DisplayName("대기열 만석 거절 전에 다른 요청이 같은 작업을 다시 대기시키면 현재 작업을 돌려준다.")
    @Test
    void returnsCurrentRequestWhenFullRejectionFollowsConcurrentResubmission() {
        CouponIssueRequest failed = stored(CouponIssueRequestStatus.FAILED, null, CouponIssueRequestReason.DB_ERROR, TOKEN);
        CouponIssueRequest waiting = stored(CouponIssueRequestStatus.WAITING, null, null, null);
        when(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.of(failed), Optional.of(waiting));
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID))
            .thenReturn(Optional.empty(), Optional.empty());
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(queue.requeueFailed(REQUEST_ID, CouponIssueRequestReason.DB_ERROR, CAPACITY))
            .thenReturn(new CouponIssueQueueResubmission.Full(failed));

        assertThat(facade.submit(CAMPAIGN_ID, USER_ID)).isEqualTo(new Accepted(waiting));
    }

    @DisplayName("시스템 실패 재접수 중 신규 수용이 닫혀도 그사이 발급된 쿠폰이 있으면 같은 작업을 성공으로 바꾼다.")
    @Test
    void marksSystemFailureSucceededWhenCouponIsIssuedBeforeAdmissionRejection() {
        admission.close();
        CouponIssueRequest failed = stored(CouponIssueRequestStatus.FAILED, null, CouponIssueRequestReason.INTERNAL_ERROR,
            TOKEN);
        CouponIssueRequest succeeded = stored(CouponIssueRequestStatus.SUCCEEDED, 105L, null, TOKEN);
        when(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.of(failed));
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID))
            .thenReturn(Optional.empty(), Optional.of(coupon()));
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(queue.markFailedSucceeded(REQUEST_ID, CouponIssueRequestReason.INTERNAL_ERROR, 105L))
            .thenReturn(new CouponIssueQueueResubmission.Succeeded(succeeded));

        assertThat(facade.submit(CAMPAIGN_ID, USER_ID)).isEqualTo(new Completed(succeeded));
    }

    @DisplayName("시스템 실패 작업을 다른 요청이 먼저 다시 대기시켰으면 바뀐 현재 작업을 돌려준다.")
    @Test
    void returnsCurrentRequestWhenResubmissionChangedMeanwhile() {
        CouponIssueRequest failed = stored(CouponIssueRequestStatus.FAILED, null, CouponIssueRequestReason.DB_ERROR, TOKEN);
        CouponIssueRequest waiting = stored(CouponIssueRequestStatus.WAITING, null, null, null);
        when(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.of(failed));
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(queue.requeueFailed(REQUEST_ID, CouponIssueRequestReason.DB_ERROR, CAPACITY))
            .thenReturn(new CouponIssueQueueResubmission.Changed(waiting));

        assertThat(facade.submit(CAMPAIGN_ID, USER_ID)).isEqualTo(new Accepted(waiting));
    }

    @DisplayName("작업은 없고 쿠폰만 있으면 작업을 만들지 않고 기존 쿠폰을 돌려준다.")
    @Test
    void returnsExistingCouponWithoutCreatingRequest() {
        when(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.of(coupon()));
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        CouponIssueQueueResult result = facade.submit(CAMPAIGN_ID, USER_ID);

        assertThat(result).isInstanceOfSatisfying(CouponAlreadyIssued.class,
            issued -> assertThat(issued.coupon().couponId()).isEqualTo(105L));
        verify(queue, never()).submit(any(), anyInt());
    }

    @DisplayName("캠페인이 없으면 거절 전에 작업과 쿠폰을 다시 확인하고, 여전히 없으면 404로 거절한다.")
    @Test
    void rejectsMissingCampaignAfterRecheck() {
        whenNoRequestAndNoCoupon();
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.empty());

        assertErrorType(() -> facade.submit(CAMPAIGN_ID, USER_ID), ErrorType.NOT_FOUND);
        verify(queue, never()).submit(any(), anyInt());
    }

    @DisplayName("거절 직전 다시 확인했을 때 그사이 생긴 작업이 있으면 그 작업을 돌려준다.")
    @Test
    void returnsRequestCreatedMeanwhileInsteadOfRejecting() {
        CouponIssueRequest waiting = stored(CouponIssueRequestStatus.WAITING, null, null, null);
        when(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty(), Optional.of(waiting));
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(soldOutCampaign()));

        assertThat(facade.submit(CAMPAIGN_ID, USER_ID)).isEqualTo(new Accepted(waiting));
    }

    @DisplayName("거절 직전 다시 확인했을 때 그사이 발급된 쿠폰이 있으면 그 쿠폰을 돌려준다.")
    @Test
    void returnsCouponIssuedMeanwhileInsteadOfRejecting() {
        when(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID))
            .thenReturn(Optional.empty(), Optional.of(coupon()));
        CouponCampaign soldOut = soldOutCampaign();
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(soldOut));

        CouponIssueQueueResult result = facade.submit(CAMPAIGN_ID, USER_ID);

        assertThat(result).isInstanceOfSatisfying(CouponAlreadyIssued.class,
            issued -> assertThat(issued.coupon().couponId()).isEqualTo(105L));
    }

    @DisplayName("거절 직전 다시 확인할 때 대기열을 읽지 못해도 그사이 발급된 쿠폰이 있으면 그 쿠폰을 돌려준다.")
    @Test
    void returnsCouponIssuedMeanwhileWhenQueueIsUnavailableOnRecheck() {
        when(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID))
            .thenReturn(Optional.empty())
            .thenThrow(new CouponIssueQueueUnavailableException(null));
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID))
            .thenReturn(Optional.empty(), Optional.of(coupon()));
        CouponCampaign soldOut = soldOutCampaign();
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(soldOut));

        CouponIssueQueueResult result = facade.submit(CAMPAIGN_ID, USER_ID);

        assertThat(result).isInstanceOfSatisfying(CouponAlreadyIssued.class,
            issued -> assertThat(issued.coupon().couponId()).isEqualTo(105L));
    }

    @DisplayName("거절 직전 다시 확인할 때 대기열을 읽지 못하고 쿠폰도 없으면 503으로 거절한다.")
    @Test
    void rejectsWhenQueueIsUnavailableOnRecheckAndNoCoupon() {
        when(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID))
            .thenReturn(Optional.empty())
            .thenThrow(new CouponIssueQueueUnavailableException(null));
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());
        CouponCampaign soldOut = soldOutCampaign();
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(soldOut));

        assertErrorType(() -> facade.submit(CAMPAIGN_ID, USER_ID), ErrorType.SERVICE_UNAVAILABLE);
    }

    @DisplayName("신규 수용이 닫혀 있으면 새 작업을 접수하지 않고 503으로 거절한다.")
    @Test
    void rejectsNewRequestWhenAdmissionIsClosed() {
        admission.close();
        whenNoRequestAndNoCoupon();
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        assertErrorType(() -> facade.submit(CAMPAIGN_ID, USER_ID), ErrorType.SERVICE_UNAVAILABLE);
        verify(queue, never()).submit(any(), anyInt());
    }

    @DisplayName("접수 한도가 차 있으면 다시 확인한 뒤 429로 거절한다.")
    @Test
    void rejectsWhenQueueIsFull() {
        whenNoRequestAndNoCoupon();
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(queue.submit(any(), eq(CAPACITY))).thenReturn(new CouponIssueQueueSubmission.Full());

        assertErrorType(() -> facade.submit(CAMPAIGN_ID, USER_ID), ErrorType.TOO_MANY_REQUESTS);
    }

    @DisplayName("접수 중 같은 사용자의 작업이 먼저 만들어졌으면 그 작업을 돌려준다.")
    @Test
    void returnsExistingRequestCreatedConcurrently() {
        CouponIssueRequest waiting = stored(CouponIssueRequestStatus.WAITING, null, null, null);
        whenNoRequestAndNoCoupon();
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(queue.submit(any(), eq(CAPACITY))).thenReturn(new CouponIssueQueueSubmission.Existing(waiting));

        assertThat(facade.submit(CAMPAIGN_ID, USER_ID)).isEqualTo(new Accepted(waiting));
    }

    @DisplayName("처음 작업을 조회할 때 대기열을 읽지 못해도 쿠폰이 있으면 그 쿠폰을 돌려준다.")
    @Test
    void returnsExistingCouponWhenQueueIsUnavailable() {
        when(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID))
            .thenThrow(new CouponIssueQueueUnavailableException(null));
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.of(coupon()));
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        assertThat(facade.submit(CAMPAIGN_ID, USER_ID)).isInstanceOf(CouponAlreadyIssued.class);
    }

    @DisplayName("처음 작업을 조회할 때 대기열을 읽지 못하고 쿠폰도 없으면 503으로 거절한다.")
    @Test
    void rejectsWhenQueueIsUnavailableAndNoCoupon() {
        when(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID))
            .thenThrow(new CouponIssueQueueUnavailableException(null));
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());

        assertErrorType(() -> facade.submit(CAMPAIGN_ID, USER_ID), ErrorType.SERVICE_UNAVAILABLE);
        verifyNoInteractions(campaigns);
    }

    @DisplayName("접수 명령의 응답을 받지 못하면 결과를 추측하지 않고 503으로 응답한다.")
    @Test
    void rejectsWhenSubmitResultIsUnknown() {
        whenNoRequestAndNoCoupon();
        when(campaigns.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(queue.submit(any(), eq(CAPACITY))).thenThrow(new CouponIssueQueueUnavailableException(null));

        assertErrorType(() -> facade.submit(CAMPAIGN_ID, USER_ID), ErrorType.SERVICE_UNAVAILABLE);
    }

    @DisplayName("대기열 저장 정보가 어긋나면 500으로 응답한다.")
    @Test
    void rejectsWhenQueueStorageIsInconsistent() {
        when(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID))
            .thenThrow(new CouponIssueQueueScriptException("저장 불일치", null));

        assertErrorType(() -> facade.submit(CAMPAIGN_ID, USER_ID), ErrorType.INTERNAL_ERROR);
    }

    @DisplayName("본인 작업만 조회하고, 없거나 다른 사용자의 작업은 같은 404로 응답한다.")
    @Test
    void returnsOnlyOwnRequest() {
        CouponIssueRequest waiting = stored(CouponIssueRequestStatus.WAITING, null, null, null);
        when(queue.findById(REQUEST_ID)).thenReturn(Optional.of(waiting));
        when(queue.findById("unknown-request-id")).thenReturn(Optional.empty());

        assertThat(facade.getOwn(REQUEST_ID, USER_ID)).isEqualTo(waiting);
        assertErrorType(() -> facade.getOwn(REQUEST_ID, USER_ID + 1), ErrorType.NOT_FOUND);
        assertErrorType(() -> facade.getOwn("unknown-request-id", USER_ID), ErrorType.NOT_FOUND);
    }

    @DisplayName("작업 조회 중 대기열을 읽지 못하면 503으로 응답한다.")
    @Test
    void rejectsLookupWhenQueueIsUnavailable() {
        when(queue.findById(REQUEST_ID)).thenThrow(new CouponIssueQueueUnavailableException(null));

        assertErrorType(() -> facade.getOwn(REQUEST_ID, USER_ID), ErrorType.SERVICE_UNAVAILABLE);
    }

    private void whenNoRequestAndNoCoupon() {
        when(queue.findByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());
        when(coupons.findIssuedByCampaignIdAndUserId(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());
    }

    private static CouponIssueRequest stored(CouponIssueRequestStatus status, Long couponId,
                                             CouponIssueRequestReason reason, String executionToken) {
        return CouponIssueRequest.reconstitute(REQUEST_ID, CAMPAIGN_ID, USER_ID, status, NOW.minusSeconds(5),
            couponId, reason, executionToken);
    }

    private static Coupon coupon() {
        return Coupon.reconstitute(105L, CAMPAIGN_ID, USER_ID, NOW.minusSeconds(2));
    }

    private static CouponCampaign soldOutCampaign() {
        return CouponCampaign.reconstitute(CAMPAIGN_ID, "테스트 행사", 5, 5, "5,000원 할인권",
            NOW.minusSeconds(60), NOW.plusSeconds(3600), NOW.minusSeconds(1));
    }

    private static void assertErrorType(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorType type) {
        assertThatThrownBy(action).isInstanceOfSatisfying(CoreException.class,
            error -> assertThat(error.getErrorType()).isEqualTo(type));
    }
}
