package com.loopers.application.coupon;

import com.loopers.application.coupon.ScriptedTransactionManager.Ending;
import com.loopers.config.CouponAdmissionProperties;
import com.loopers.config.CouponCacheProperties;
import com.loopers.config.CouponWaitingProperties;
import com.loopers.domain.coupon.CouponAdmissionRepository;
import com.loopers.domain.coupon.CouponAdmissionRepository.FinishOutcome;
import com.loopers.domain.coupon.CouponAdmissionRepository.Start;
import com.loopers.domain.coupon.CouponAdmissionRepository.StartOutcome;
import com.loopers.domain.coupon.CouponBenefitType;
import com.loopers.domain.coupon.CouponCacheRepository;
import com.loopers.domain.coupon.CouponCacheRepository.IssuedCoupon;
import com.loopers.domain.coupon.CouponCampaignIssueData;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponCampaignStatus;
import com.loopers.domain.coupon.CouponModel;
import com.loopers.domain.coupon.CouponQueryRepository;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.CouponWaitingState;
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
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Facade 의 TX 종료 판정과 대기 종료 기록(finish) 분기(설계 §6.1·§10.2·§12.12, Q8·Q13 과 Q11 의 대역 부분).
 * 발급은 start 가 STARTED 를 돌려준 뒤에만 DB 로 들어가며, 종료가 확인된 경우에만 finish 를 한 번 부른다.
 * TX 관리자와 저장소는 대역이다. 실제 MySQL commit/rollback 관찰은 CouponFacadeIntegrationTest 가 따로 본다.
 * TX 순서는 사전 읽기 → 쓰기 → (필요하면) 후속 읽기이며, 대역 스크립트도 이 순서로 종료 방식을 지정한다.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Facade 는 예외 종류보다 TX 종료 판정을 먼저 적용하고, 종료 불명이면 PROCESSING 을 남긴 채 닫으며, 그 밖에는 종료 뒤 finish 한다.")
class CouponFacadeTransactionOutcomeTest {

    private static final Long USER = 11L;
    private static final Long CAMPAIGN = 7L;
    private static final Long COUPON_ID = 77L;
    private static final String WAITING_ID = "waiting-1";

    private ScriptedTransactionManager tm;
    private CouponQueryRepository queryRepository;
    private CouponCampaignRepository campaignRepository;
    private CouponRepository couponRepository;
    private CouponAdmissionRepository admissionRepository;
    private CouponAdmissionService admission;
    private CouponCacheRepository cacheRepository;
    private CouponFacade facade;

    @BeforeEach
    void setUp() {
        tm = new ScriptedTransactionManager();
        queryRepository = mock(CouponQueryRepository.class);
        campaignRepository = mock(CouponCampaignRepository.class);
        couponRepository = mock(CouponRepository.class);
        admissionRepository = mock(CouponAdmissionRepository.class);
        admission = new CouponAdmissionService(admissionRepository, new CouponAdmissionProperties(3),
            new CouponWaitingProperties(CAMPAIGN, Duration.ofSeconds(1), Duration.ofSeconds(30)));
        admission.open();
        // 기본 mock 은 조회 empty·저장 no-op 이라 캐시 도입 전 분기를 그대로 탄다(설계 §13.12 C1). 캐시 분기 검사만 stub 한다.
        cacheRepository = mock(CouponCacheRepository.class);
        facade = new CouponFacade(tm, queryRepository, campaignRepository, couponRepository, new ConstraintViolationClassifier(), admission,
            new CouponCacheService(cacheRepository, new CouponCacheProperties(Duration.ofSeconds(30), Duration.ofSeconds(30))));

        when(queryRepository.findOwned(USER, CAMPAIGN)).thenReturn(Optional.empty());
        when(admissionRepository.start(CAMPAIGN, USER, WAITING_ID)).thenReturn(new Start(StartOutcome.STARTED, null));
        when(admissionRepository.finish(eq(CAMPAIGN), eq(USER), eq(WAITING_ID), any())).thenReturn(FinishOutcome.APPLIED);
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

    private CouponIssueResult issue() {
        return facade.issue(USER, CAMPAIGN, WAITING_ID).result();
    }

    /** 종료 기록은 한 번, 지정 결과로만. */
    private void verifyFinishedOnce(CouponWaitingState outcome) {
        verify(admissionRepository).finish(eq(CAMPAIGN), eq(USER), eq(WAITING_ID), any());
        verify(admissionRepository).finish(CAMPAIGN, USER, WAITING_ID, outcome);
    }

    private static ErrorType errorTypeOf(Throwable thrown) {
        return ((CoreException) thrown).getErrorType();
    }

    @Nested
    @DisplayName("쓰기 TX")
    class Write {

        @DisplayName("정상 commit 이면 CREATED 이고, 쓰기 콜백에서 afterCompletion 등록이 수락됐으며, finish(ISSUED) 를 한 번 부른다.")
        @Test
        void committed() {
            CouponIssueResult result = issue();

            assertAll(
                () -> assertThat(result.created()).isTrue(),
                () -> assertThat(result.couponId()).isEqualTo(COUPON_ID),
                () -> assertThat(tm.registeredSynchronizations()).containsExactly(0, 1),
                () -> verifyFinishedOnce(CouponWaitingState.ISSUED),
                () -> assertThat(admission.status()).isTrue()
            );
        }

        @DisplayName("TX 시작 실패(콜백 미진입)는 업무 미시작이라 종료 불명이 아니다. 저장소 미호출·예외 전파·finish(FAILED)·수용 유지.")
        @Test
        void beginFailsIsNotStarted() {
            tm.then(Ending.NORMAL).then(Ending.BEGIN_FAILS);

            assertThatThrownBy(() -> issue()).isInstanceOf(CannotCreateTransactionException.class);

            assertAll(
                () -> verify(campaignRepository, never()).reserveOne(any(), any()),
                () -> verifyFinishedOnce(CouponWaitingState.FAILED),
                () -> assertThat(admission.status()).isTrue()
            );
        }

        @DisplayName("콜백 진입 뒤 commit 이 UNKNOWN 이면 500·finish 없음(PROCESSING 유지)·수용 닫힘·waitingId/사용자/캠페인 로그다.")
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

        @DisplayName("COMMITTED 통지 뒤 예외여도 확보한 결과가 있으면 CREATED 를 유지하고 finish(ISSUED) 한다.")
        @Test
        void committedThenException() {
            tm.then(Ending.NORMAL).then(Ending.COMMITTED_THEN_THROWS);

            CouponIssueResult result = issue();

            assertAll(
                () -> assertThat(result.created()).isTrue(),
                () -> assertThat(result.couponId()).isEqualTo(COUPON_ID),
                () -> verifyFinishedOnce(CouponWaitingState.ISSUED),
                () -> assertThat(admission.status()).isTrue()
            );
        }

        @DisplayName("COMMITTED 통지가 있지만 결과를 확보하지 못했으면 500 을 유지하되, commit 은 확인했으므로 finish(ISSUED) 다.")
        @Test
        void committedWithoutCandidate() {
            tm.then(Ending.NORMAL).then(Ending.ROLLBACK_REPORTS_COMMITTED);
            doThrow(new IllegalStateException("결과 복사 실패(대역)")).when(couponRepository).save(any());

            assertThatThrownBy(() -> issue())
                .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.INTERNAL_ERROR));
            assertAll(
                () -> verifyFinishedOnce(CouponWaitingState.ISSUED),
                () -> assertThat(admission.status()).isTrue()
            );
        }

        @DisplayName("UPDATE 0 건 신호가 있어도 rollback 이 UNKNOWN 이면 품절 판별로 가지 않고 500·finish 없음·닫힘이다.")
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

        @DisplayName("rollback 확인 뒤 정확한 유일 제약이면 새 읽기에서 기존 결과(STORED)를 돌려주고 finish(ISSUED) 한다.")
        @Test
        void uniqueThenStored() {
            doThrow((RuntimeException) uniqueViolation("coupon.uk_coupon_campaign_user")).when(couponRepository).save(any());
            when(queryRepository.findOwned(USER, CAMPAIGN)).thenReturn(Optional.empty(), Optional.of(owned()));

            CouponIssueResult result = issue();

            assertAll(
                () -> assertThat(result.created()).isFalse(),
                () -> assertThat(result.couponId()).isEqualTo(COUPON_ID),
                () -> assertThat(tm.begun()).isEqualTo(3),
                () -> verifyFinishedOnce(CouponWaitingState.ISSUED)
            );
        }

        @DisplayName("CHECK·다른 제약 위반은 중복 성공·품절로 바꾸지 않고 원래 오류로 끝내며 후속 읽기를 하지 않는다.")
        @Test
        void otherConstraintStaysError() {
            RuntimeException check = (RuntimeException) uniqueViolation("coupon_campaign.ck_coupon_campaign_quota");
            doThrow(check).when(couponRepository).save(any());

            assertThatThrownBy(() -> issue()).isSameAs(check);
            assertAll(
                () -> assertThat(tm.begun()).isEqualTo(2),
                () -> verifyFinishedOnce(CouponWaitingState.FAILED)
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

        @DisplayName("후속 읽기 commit 이 UNKNOWN 이면 확보한 값이 있어도 종료 불명이다. 500·finish 없음·닫힘.")
        @Test
        void readUnknown(CapturedOutput output) {
            tm.then(Ending.NORMAL).then(Ending.NORMAL).then(Ending.COMMIT_UNKNOWN);
            when(queryRepository.findOwned(USER, CAMPAIGN)).thenReturn(Optional.empty(), Optional.of(owned()));

            assertUnknownOutcome(output, "후속 읽기 TX");
        }

        @DisplayName("후속 읽기 시작 실패는 앞선 rollback 확인을 유지한다. 오류 전파·finish(FAILED)·수용 유지.")
        @Test
        void readBeginFails() {
            tm.then(Ending.NORMAL).then(Ending.NORMAL).then(Ending.BEGIN_FAILS);

            assertThatThrownBy(() -> issue()).isInstanceOf(CannotCreateTransactionException.class);
            assertAll(
                () -> verifyFinishedOnce(CouponWaitingState.FAILED),
                () -> assertThat(admission.status()).isTrue()
            );
        }

        @DisplayName("후속 읽기의 업무 거절(비활성)은 rollback 통지로 종료를 확인하고 409 로 끝나며 finish(FAILED) 한다.")
        @Test
        void readRejection() {
            CouponCampaignModel inactive = mock(CouponCampaignModel.class);
            when(inactive.getStatus()).thenReturn(CouponCampaignStatus.INACTIVE);
            when(campaignRepository.findById(CAMPAIGN)).thenReturn(Optional.of(inactive));

            assertThatThrownBy(() -> issue())
                .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.COUPON_CAMPAIGN_INACTIVE));
            verifyFinishedOnce(CouponWaitingState.FAILED);
        }
    }

    @Nested
    @DisplayName("종료 기록 이상(Q8 대응)")
    class FinishFailure {

        @DisplayName("finish 는 모든 DB TX 가 끝난 뒤 호출된다(동기화 비활성·사전 읽기와 쓰기 2회 종료 뒤).")
        @Test
        void finishRunsAfterTransactions() {
            List<String> observed = new ArrayList<>();
            when(admissionRepository.finish(CAMPAIGN, USER, WAITING_ID, CouponWaitingState.ISSUED)).thenAnswer(invocation -> {
                observed.add("sync=" + TransactionSynchronizationManager.isSynchronizationActive() + " begun=" + tm.begun());
                return FinishOutcome.APPLIED;
            });

            issue();

            assertThat(observed).containsExactly("sync=false begun=2");
        }

        @DisplayName("UNCHANGED 는 CREATED 를 유지하고 수용도 유지하며, waitingId·사용자·캠페인·결과를 로그로 남긴다.")
        @Test
        void unchangedKeepsCreatedAndOpen(CapturedOutput output) {
            when(admissionRepository.finish(CAMPAIGN, USER, WAITING_ID, CouponWaitingState.ISSUED)).thenReturn(FinishOutcome.UNCHANGED);

            CouponIssueResult result = issue();

            assertAll(
                () -> assertThat(result.created()).isTrue(),
                () -> assertThat(admission.status()).isTrue(),
                () -> assertThat(output).contains("waitingId = " + WAITING_ID, "userId = " + USER, "campaignId = " + CAMPAIGN,
                    "result = CREATED couponId " + COUPON_ID)
            );
        }

        @DisplayName("INVARIANT_ERROR·명령 예외는 CREATED 를 덮어쓰지 않고 닫힌다.")
        @Test
        void failureKeepsCreatedAndCloses() {
            when(admissionRepository.finish(CAMPAIGN, USER, WAITING_ID, CouponWaitingState.ISSUED))
                .thenReturn(FinishOutcome.INVARIANT_ERROR).thenThrow(new QueryTimeoutException("timeout(대역)"));

            for (int i = 0; i < 2; i++) {
                admission.open();
                CouponIssueResult result = issue();
                assertThat(result.created()).isTrue();
                assertThat(admission.status()).isFalse();
            }
        }

        @DisplayName("업무 거절 뒤 종료 기록 실패여도 원래 업무 거절을 유지한다.")
        @Test
        void failureKeepsRejection() {
            when(campaignRepository.reserveOne(any(), any())).thenReturn(0);
            when(campaignRepository.findById(CAMPAIGN)).thenReturn(Optional.empty());
            when(admissionRepository.finish(CAMPAIGN, USER, WAITING_ID, CouponWaitingState.FAILED))
                .thenThrow(new QueryTimeoutException("timeout(대역)"));

            assertThatThrownBy(() -> issue())
                .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.COUPON_CAMPAIGN_NOT_FOUND));
            assertThat(admission.status()).isFalse();
        }
    }

    @Nested
    @DisplayName("기발급 캐시 저장(설계 §13.10, KQ4 의 대역 부분)")
    class CacheFill {

        private final IssuedCoupon value = new IssuedCoupon(COUPON_ID, CAMPAIGN, "가을 캠페인", CouponBenefitType.FIXED_AMOUNT, 5_000L);

        @DisplayName("신규 발급은 쓰기 TX 종료와 finish 뒤에 확보한 결과로 저장한다. 캐시용 DB 재조회는 없다.")
        @Test
        void createdRememberedAfterFinish() {
            List<String> observed = new ArrayList<>();
            doAnswer(invocation -> observed.add("sync=" + TransactionSynchronizationManager.isSynchronizationActive() + " begun=" + tm.begun()))
                .when(cacheRepository).putIssuedIfAbsent(any(), any(), any(), anyLong());

            CouponIssueResult result = issue();

            InOrder order = inOrder(admissionRepository, cacheRepository);
            order.verify(admissionRepository).finish(CAMPAIGN, USER, WAITING_ID, CouponWaitingState.ISSUED);
            order.verify(cacheRepository).putIssuedIfAbsent(USER, CAMPAIGN, value, 30_000L);
            assertAll(
                () -> assertThat(result.created()).isTrue(),
                () -> assertThat(observed).containsExactly("sync=false begun=2"),
                () -> verify(queryRepository, times(1)).findOwned(USER, CAMPAIGN)
            );
        }

        @DisplayName("COMMITTED 뒤 예외여도 결과를 확보했으면 저장하고, 유일 제약 뒤 후속 읽기 결과도 모든 TX·finish 뒤에 저장한다.")
        @Test
        void confirmedResultsAreRemembered() {
            tm.then(Ending.NORMAL).then(Ending.COMMITTED_THEN_THROWS);
            assertThat(issue().created()).isTrue();

            doThrow((RuntimeException) uniqueViolation("coupon.uk_coupon_campaign_user")).when(couponRepository).save(any());
            when(queryRepository.findOwned(USER, CAMPAIGN)).thenReturn(Optional.empty(), Optional.of(owned()));
            assertThat(issue().created()).isFalse();

            verify(cacheRepository, times(2)).putIssuedIfAbsent(USER, CAMPAIGN, value, 30_000L);
        }

        @DisplayName("종료 불명·commit 확인 뒤 결과 미확보·시작 실패·업무 거절은 저장하지 않고, 캐시를 채우려고 DB 를 다시 읽지 않는다.")
        @Test
        void unconfirmedResultsAreNotRemembered(CapturedOutput output) {
            tm.then(Ending.NORMAL).then(Ending.COMMIT_UNKNOWN);
            assertUnknownOutcome(output, "발급 쓰기 TX");

            admission.open();
            tm.then(Ending.NORMAL).then(Ending.ROLLBACK_REPORTS_COMMITTED);
            doThrow(new IllegalStateException("결과 복사 실패(대역)")).when(couponRepository).save(any());
            assertThatThrownBy(() -> issue()).satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.INTERNAL_ERROR));

            tm.then(Ending.NORMAL).then(Ending.BEGIN_FAILS);
            assertThatThrownBy(() -> issue()).isInstanceOf(CannotCreateTransactionException.class);

            when(campaignRepository.reserveOne(any(), any())).thenReturn(0);
            when(campaignRepository.findById(CAMPAIGN)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> issue()).satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.COUPON_CAMPAIGN_NOT_FOUND));

            assertAll(
                () -> verify(cacheRepository, never()).putIssuedIfAbsent(any(), any(), any(), anyLong()),
                // 사전 읽기 4회 + 마지막 사례의 후속 읽기 1회. 결과 미확보 분기에 추가 조회가 없다.
                () -> verify(queryRepository, times(5)).findOwned(USER, CAMPAIGN)
            );
        }

        @DisplayName("finish 실패와 캐시 저장 실패는 서로의 결과를 바꾸지 않고, 확정된 CREATED 를 유지하며 저장을 다시 시도하지 않는다.")
        @Test
        void finishAndPutFailuresKeepCreated() {
            when(admissionRepository.finish(CAMPAIGN, USER, WAITING_ID, CouponWaitingState.ISSUED))
                .thenThrow(new QueryTimeoutException("finish timeout(대역)")).thenReturn(FinishOutcome.APPLIED);
            doThrow(new QueryTimeoutException("캐시 저장 timeout(대역)")).when(cacheRepository).putIssuedIfAbsent(any(), any(), any(), anyLong());

            CouponIssueResult afterFinishFailure = issue();
            admission.open();
            CouponIssueResult afterPutFailure = issue();

            assertAll(
                () -> assertThat(afterFinishFailure.created()).isTrue(),
                () -> assertThat(afterPutFailure.created()).isTrue(),
                () -> verify(cacheRepository, times(2)).putIssuedIfAbsent(USER, CAMPAIGN, value, 30_000L),
                () -> assertThat(admission.status()).isTrue()
            );
        }

        @DisplayName("응답 유실 뒤 재요청이 캐시 hit 이면 start·TX·finish 없이 기존 결과(created=false)라 추가 효과가 없다.")
        @Test
        void cachedRetryHasNoEffect() {
            when(cacheRepository.findIssued(USER, CAMPAIGN)).thenReturn(Optional.of(value));

            CouponIssueResult result = issue();

            assertAll(
                () -> assertThat(result).isEqualTo(new CouponIssueResult(COUPON_ID, CAMPAIGN, "가을 캠페인", CouponBenefitType.FIXED_AMOUNT, 5_000L, false)),
                () -> assertThat(tm.begun()).isZero(),
                () -> verify(admissionRepository, never()).start(any(), any(), any()),
                () -> verify(admissionRepository, never()).finish(any(), any(), any(), any())
            );
        }
    }

    private void assertUnknownOutcome(CapturedOutput output, String stage) {
        assertThatThrownBy(() -> issue())
            .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.INTERNAL_ERROR));
        assertAll(
            () -> verify(admissionRepository, never()).finish(any(), any(), any(), any()),
            () -> assertThat(admission.status()).isFalse(),
            () -> assertThat(output).contains("waitingId = " + WAITING_ID, "userId = " + USER, "campaignId = " + CAMPAIGN,
                "stage = " + stage, "result = 불명")
        );
    }
}
