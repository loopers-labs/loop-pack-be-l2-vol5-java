package com.loopers.application.coupon;

import com.loopers.application.coupon.ScriptedTransactionManager.Ending;
import com.loopers.config.CouponAdmissionProperties;
import com.loopers.domain.coupon.CouponAdmissionRepository;
import com.loopers.domain.coupon.CouponAdmissionRepository.Acquisition;
import com.loopers.domain.coupon.CouponBenefitType;
import com.loopers.domain.coupon.CouponCampaignIssueData;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponCampaignStatus;
import com.loopers.domain.coupon.CouponModel;
import com.loopers.domain.coupon.CouponQueryRepository;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.OwnedCoupon;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.persistence.ConstraintViolationClassifier;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Facade 의 TX 종료 판정과 token 반환 분기(설계 §6.1·§10.2, Q8·Q13 과 Q11 의 대역 부분).
 * TX 관리자와 저장소는 대역이다. 실제 MySQL commit/rollback 관찰은 CouponFacadeIntegrationTest 가 따로 본다.
 * TX 순서는 사전 읽기 → 쓰기 → (필요하면) 후속 읽기이며, 대역 스크립트도 이 순서로 종료 방식을 지정한다.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Facade 는 예외 종류보다 TX 종료 판정을 먼저 적용하고, 종료 불명이면 token 을 남긴 채 닫으며, 그 밖에는 종료 뒤 token 을 반환한다.")
class CouponFacadeTransactionOutcomeTest {

    private static final Long USER = 11L;
    private static final Long CAMPAIGN = 7L;
    private static final Long COUPON_ID = 77L;

    private ScriptedTransactionManager tm;
    private CouponQueryRepository queryRepository;
    private CouponCampaignRepository campaignRepository;
    private CouponRepository couponRepository;
    private CouponAdmissionRepository admissionRepository;
    private CouponAdmissionService admission;
    private CouponFacade facade;

    @BeforeEach
    void setUp() {
        tm = new ScriptedTransactionManager();
        queryRepository = mock(CouponQueryRepository.class);
        campaignRepository = mock(CouponCampaignRepository.class);
        couponRepository = mock(CouponRepository.class);
        admissionRepository = mock(CouponAdmissionRepository.class);
        admission = new CouponAdmissionService(admissionRepository, new CouponAdmissionProperties(3));
        admission.open();
        facade = new CouponFacade(tm, queryRepository, campaignRepository, couponRepository,
            new ConstraintViolationClassifier(), admission);

        when(queryRepository.findOwned(USER, CAMPAIGN)).thenReturn(Optional.empty());
        when(admissionRepository.tryAcquire(anyString(), anyInt())).thenReturn(Acquisition.ACQUIRED);
        when(admissionRepository.release(anyString())).thenReturn(1L);
        when(campaignRepository.reserveOne(any(), any())).thenReturn(1);
        when(campaignRepository.findIssueData(CAMPAIGN))
            .thenReturn(Optional.of(new CouponCampaignIssueData(CAMPAIGN, "가을 캠페인", CouponBenefitType.FIXED_AMOUNT, 5_000L)));
        when(couponRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0)));
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static CouponModel withId(CouponModel coupon) {
        ReflectionTestUtils.setField(coupon, "id", COUPON_ID);
        return coupon;
    }

    private static OwnedCoupon owned() {
        return new OwnedCoupon(COUPON_ID, CAMPAIGN, "가을 캠페인", CouponBenefitType.FIXED_AMOUNT, 5_000L, ZonedDateTime.now());
    }

    private static Throwable uniqueViolation(String name) {
        return new DataIntegrityViolationException("duplicate",
            new ConstraintViolationException("violation", new SQLException("duplicate", "23000", 1062), name));
    }

    private String acquiredToken() {
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(admissionRepository).tryAcquire(token.capture(), anyInt());
        return token.getValue();
    }

    private void verifyReleasedOnce() {
        String token = acquiredToken();
        verify(admissionRepository).release(token);
    }

    private static ErrorType errorTypeOf(Throwable thrown) {
        return ((CoreException) thrown).getErrorType();
    }

    @Nested
    @DisplayName("쓰기 TX")
    class Write {

        @DisplayName("정상 commit 이면 CREATED 이고, 쓰기 콜백에서 afterCompletion 등록이 수락됐으며, token 을 한 번 반환한다.")
        @Test
        void committed() {
            CouponIssueResult result = facade.issue(USER, CAMPAIGN);

            assertAll(
                () -> assertThat(result.created()).isTrue(),
                () -> assertThat(result.couponId()).isEqualTo(COUPON_ID),
                () -> assertThat(tm.registeredSynchronizations()).containsExactly(0, 1),
                () -> verifyReleasedOnce(),
                () -> assertThat(admission.status()).isTrue()
            );
        }

        @DisplayName("TX 시작 실패(콜백 미진입)는 업무 미시작이라 종료 불명이 아니다. 저장소 미호출·예외 전파·token 반환·수용 유지.")
        @Test
        void beginFailsIsNotStarted() {
            tm.then(Ending.NORMAL).then(Ending.BEGIN_FAILS);

            assertThatThrownBy(() -> facade.issue(USER, CAMPAIGN)).isInstanceOf(CannotCreateTransactionException.class);

            assertAll(
                () -> verify(campaignRepository, never()).reserveOne(any(), any()),
                () -> verifyReleasedOnce(),
                () -> assertThat(admission.status()).isTrue()
            );
        }

        @DisplayName("콜백 진입 뒤 commit 이 UNKNOWN 이면 500·token 유지·수용 닫힘·token/사용자/캠페인 로그다.")
        @Test
        void commitUnknown(CapturedOutput output) {
            tm.then(Ending.NORMAL).then(Ending.COMMIT_UNKNOWN);

            assertUnknownOutcome(output, "발급 쓰기 TX");
        }

        @DisplayName("콜백 진입 뒤 완료 통지 없이 실패해도 종료 불명이다.")
        @Test
        void commitWithoutNotice(CapturedOutput output) {
            tm.then(Ending.NORMAL).then(Ending.COMMIT_NO_NOTICE);

            assertUnknownOutcome(output, "발급 쓰기 TX");
        }

        @DisplayName("COMMITTED 통지 뒤 예외여도 확보한 결과가 있으면 CREATED 를 유지하고 token 을 반환한다.")
        @Test
        void committedThenException() {
            tm.then(Ending.NORMAL).then(Ending.COMMITTED_THEN_THROWS);

            CouponIssueResult result = facade.issue(USER, CAMPAIGN);

            assertAll(
                () -> assertThat(result.created()).isTrue(),
                () -> assertThat(result.couponId()).isEqualTo(COUPON_ID),
                () -> verifyReleasedOnce(),
                () -> assertThat(admission.status()).isTrue()
            );
        }

        @DisplayName("COMMITTED 통지가 있지만 결과를 확보하지 못했으면 500 이고 저장 효과를 되돌리지 않으며 token 은 반환한다.")
        @Test
        void committedWithoutCandidate() {
            tm.then(Ending.NORMAL).then(Ending.ROLLBACK_REPORTS_COMMITTED);
            doThrow(new IllegalStateException("결과 복사 실패(대역)")).when(couponRepository).save(any());

            assertThatThrownBy(() -> facade.issue(USER, CAMPAIGN))
                .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.INTERNAL_ERROR));
            assertAll(
                () -> verifyReleasedOnce(),
                () -> assertThat(admission.status()).isTrue()
            );
        }

        @DisplayName("UPDATE 0 건 신호가 있어도 rollback 이 UNKNOWN 이면 품절 판별로 가지 않고 500·token 유지·닫힘이다.")
        @Test
        void notReservedButRollbackUnknown(CapturedOutput output) {
            tm.then(Ending.NORMAL).then(Ending.ROLLBACK_UNKNOWN);
            when(campaignRepository.reserveOne(any(), any())).thenReturn(0);

            assertUnknownOutcome(output, "발급 쓰기 TX");
            verify(campaignRepository, never()).findById(anyLong());
            verify(queryRepository, times(1)).findOwned(USER, CAMPAIGN);
        }

        @DisplayName("정확한 유일 제약 신호가 있어도 rollback 이 UNKNOWN 이면 기존 결과로 바꾸지 않는다.")
        @Test
        void uniqueButRollbackUnknown(CapturedOutput output) {
            tm.then(Ending.NORMAL).then(Ending.ROLLBACK_UNKNOWN);
            doThrow((RuntimeException) uniqueViolation("coupon.uk_coupon_campaign_user")).when(couponRepository).save(any());

            assertUnknownOutcome(output, "발급 쓰기 TX");
            verify(queryRepository, times(1)).findOwned(USER, CAMPAIGN);
        }

        @DisplayName("rollback 확인 뒤 정확한 유일 제약이면 새 읽기에서 기존 결과(STORED)를 돌려주고 token 을 반환한다.")
        @Test
        void uniqueThenStored() {
            doThrow((RuntimeException) uniqueViolation("coupon.uk_coupon_campaign_user")).when(couponRepository).save(any());
            when(queryRepository.findOwned(USER, CAMPAIGN)).thenReturn(Optional.empty(), Optional.of(owned()));

            CouponIssueResult result = facade.issue(USER, CAMPAIGN);

            assertAll(
                () -> assertThat(result.created()).isFalse(),
                () -> assertThat(result.couponId()).isEqualTo(COUPON_ID),
                () -> assertThat(tm.begun()).isEqualTo(3),
                () -> verifyReleasedOnce()
            );
        }

        @DisplayName("CHECK·다른 제약 위반은 중복 성공·품절로 바꾸지 않고 원래 오류로 끝내며 후속 읽기를 하지 않는다.")
        @Test
        void otherConstraintStaysError() {
            RuntimeException check = (RuntimeException) uniqueViolation("coupon_campaign.ck_coupon_campaign_quota");
            doThrow(check).when(couponRepository).save(any());

            assertThatThrownBy(() -> facade.issue(USER, CAMPAIGN)).isSameAs(check);
            assertAll(
                () -> assertThat(tm.begun()).isEqualTo(2),
                () -> verifyReleasedOnce()
            );
        }
    }

    @Nested
    @DisplayName("후속 읽기 TX")
    class FollowUp {

        @BeforeEach
        void notReserved() {
            when(campaignRepository.reserveOne(any(), any())).thenReturn(0);
        }

        @DisplayName("후속 읽기 commit 이 UNKNOWN 이면 확보한 값이 있어도 종료 불명이다. 500·token 유지·닫힘.")
        @Test
        void readUnknown(CapturedOutput output) {
            tm.then(Ending.NORMAL).then(Ending.NORMAL).then(Ending.COMMIT_UNKNOWN);
            when(queryRepository.findOwned(USER, CAMPAIGN)).thenReturn(Optional.empty(), Optional.of(owned()));

            assertUnknownOutcome(output, "후속 읽기 TX");
        }

        @DisplayName("후속 읽기 시작 실패는 앞선 rollback 확인을 유지한다. 오류 전파·token 반환·수용 유지.")
        @Test
        void readBeginFails() {
            tm.then(Ending.NORMAL).then(Ending.NORMAL).then(Ending.BEGIN_FAILS);

            assertThatThrownBy(() -> facade.issue(USER, CAMPAIGN)).isInstanceOf(CannotCreateTransactionException.class);
            assertAll(
                () -> verifyReleasedOnce(),
                () -> assertThat(admission.status()).isTrue()
            );
        }

        @DisplayName("후속 읽기의 업무 거절(비활성)은 rollback 통지로 종료를 확인하고 409 로 끝나며 token 을 반환한다.")
        @Test
        void readRejection() {
            CouponCampaignModel inactive = mock(CouponCampaignModel.class);
            when(inactive.getStatus()).thenReturn(CouponCampaignStatus.INACTIVE);
            when(campaignRepository.findById(CAMPAIGN)).thenReturn(Optional.of(inactive));

            assertThatThrownBy(() -> facade.issue(USER, CAMPAIGN))
                .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.COUPON_CAMPAIGN_INACTIVE));
            verifyReleasedOnce();
        }
    }

    @Nested
    @DisplayName("token 반환 이상(Q8)")
    class ReleaseFailure {

        @DisplayName("정상 첫 반환이 0 이어도 CREATED 결과는 유지되고 수용이 닫히며 사용자·캠페인·결과를 로그로 남긴다.")
        @Test
        void releaseZeroKeepsCreated(CapturedOutput output) {
            when(admissionRepository.release(anyString())).thenReturn(0L);

            CouponIssueResult result = facade.issue(USER, CAMPAIGN);

            String token = acquiredToken();
            assertAll(
                () -> assertThat(result.created()).isTrue(),
                () -> assertThat(admission.status()).isFalse(),
                () -> assertThat(output).contains("token = " + token, "userId = " + USER, "campaignId = " + CAMPAIGN,
                    "result = CREATED couponId " + COUPON_ID)
            );
        }

        @DisplayName("반환 명령 예외도 CREATED 를 덮어쓰지 않고 닫힌다.")
        @Test
        void releaseFailureKeepsCreated() {
            when(admissionRepository.release(anyString())).thenThrow(new QueryTimeoutException("timeout(대역)"));

            CouponIssueResult result = facade.issue(USER, CAMPAIGN);

            assertAll(
                () -> assertThat(result.created()).isTrue(),
                () -> assertThat(admission.status()).isFalse()
            );
        }

        @DisplayName("업무 거절 뒤 반환 실패여도 원래 업무 거절을 유지한다.")
        @Test
        void releaseFailureKeepsRejection() {
            when(campaignRepository.reserveOne(any(), any())).thenReturn(0);
            when(campaignRepository.findById(CAMPAIGN)).thenReturn(Optional.empty());
            when(admissionRepository.release(anyString())).thenThrow(new QueryTimeoutException("timeout(대역)"));

            assertThatThrownBy(() -> facade.issue(USER, CAMPAIGN))
                .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.COUPON_CAMPAIGN_NOT_FOUND));
            assertThat(admission.status()).isFalse();
        }
    }

    private void assertUnknownOutcome(CapturedOutput output, String stage) {
        assertThatThrownBy(() -> facade.issue(USER, CAMPAIGN))
            .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.INTERNAL_ERROR));
        String token = acquiredToken();
        assertAll(
            () -> verify(admissionRepository, never()).release(anyString()),
            () -> assertThat(admission.status()).isFalse(),
            () -> assertThat(output).contains("token = " + token, "userId = " + USER, "campaignId = " + CAMPAIGN,
                "stage = " + stage, "result = 불명")
        );
    }
}
