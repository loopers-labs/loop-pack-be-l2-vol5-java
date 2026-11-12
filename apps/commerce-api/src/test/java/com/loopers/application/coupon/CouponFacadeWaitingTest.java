package com.loopers.application.coupon;

import com.loopers.config.CouponAdmissionProperties;
import com.loopers.config.CouponWaitingProperties;
import com.loopers.domain.coupon.CouponAdmissionRepository;
import com.loopers.domain.coupon.CouponAdmissionRepository.AdmissionProgress;
import com.loopers.domain.coupon.CouponAdmissionRepository.Registration;
import com.loopers.domain.coupon.CouponAdmissionRepository.RegistrationOutcome;
import com.loopers.domain.coupon.CouponAdmissionRepository.Start;
import com.loopers.domain.coupon.CouponAdmissionRepository.StartOutcome;
import com.loopers.domain.coupon.CouponBenefitType;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponCampaignStatus;
import com.loopers.domain.coupon.CouponQueryRepository;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.CouponWaitingSnapshot;
import com.loopers.domain.coupon.CouponWaitingState;
import com.loopers.domain.coupon.OwnedCoupon;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.persistence.ConstraintViolationClassifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Facade 의 대기 등록·조회·발급 진입 순서와 입장 처리(설계 §12.9·12.12). 저장소·TX 관리자는 대역이다.
 * 실제 Redis·MySQL 경로는 CouponFacadeIntegrationTest, TX 종료 판정은 CouponFacadeTransactionOutcomeTest 가 본다.
 */
@DisplayName("Facade 는 기존 쿠폰을 먼저 보고, 운영 캠페인·열림·헤더 조건을 Redis 전에 확인하며, 진행 중·발급됨 관찰은 DB 를 한 번 다시 읽는다.")
class CouponFacadeWaitingTest {

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
        facade = new CouponFacade(tm, queryRepository, campaignRepository, couponRepository, new ConstraintViolationClassifier(), admission);
        when(queryRepository.findOwned(USER, CAMPAIGN)).thenReturn(Optional.empty());
        CouponCampaignModel active = campaign(CouponCampaignStatus.ACTIVE, 0, 5);
        when(campaignRepository.findById(CAMPAIGN)).thenReturn(Optional.of(active));
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static CouponCampaignModel campaign(CouponCampaignStatus status, long issued, long quota) {
        CouponCampaignModel campaign = mock(CouponCampaignModel.class);
        when(campaign.getStatus()).thenReturn(status);
        when(campaign.getIssuedCount()).thenReturn(issued);
        when(campaign.getTotalQuota()).thenReturn(quota);
        return campaign;
    }

    private static OwnedCoupon owned() {
        return new OwnedCoupon(COUPON_ID, CAMPAIGN, "가을 캠페인", CouponBenefitType.FIXED_AMOUNT, 5_000L, ZonedDateTime.now());
    }

    private static CouponWaitingSnapshot snapshot(CouponWaitingState status) {
        return new CouponWaitingSnapshot(CAMPAIGN, WAITING_ID, status, status == CouponWaitingState.WAITING ? 2L : null,
            status == CouponWaitingState.READY ? Instant.ofEpochMilli(1_763_000_000_000L) : null, null);
    }

    private static CouponWaitingSnapshot issuedSnapshot(String waitingId) {
        return new CouponWaitingSnapshot(CAMPAIGN, waitingId, CouponWaitingState.ISSUED, null, null, COUPON_ID);
    }

    private static void assertError(Runnable call, ErrorType expected) {
        assertThatThrownBy(call::run).satisfies(e -> assertThat(((CoreException) e).getErrorType()).isEqualTo(expected));
    }

    @Nested
    @DisplayName("registerWaiting")
    class Register {

        @DisplayName("기존 쿠폰이 있으면 닫혀 있어도 Redis 없이 ISSUED·couponId 이고 새 항목이 아니다.")
        @Test
        void storedFirst() {
            admission.close();
            when(queryRepository.findOwned(USER, CAMPAIGN)).thenReturn(Optional.of(owned()));

            CouponWaitingResult result = facade.registerWaiting(USER, CAMPAIGN);

            assertThat(result).isEqualTo(new CouponWaitingResult(false, issuedSnapshot(null)));
            verifyNoInteractions(admissionRepository);
        }

        @DisplayName("닫힘·운영 ID 불일치는 캠페인 조회·Redis 전에 503 이다.")
        @Test
        void closedOrOtherCampaign() {
            admission.close();
            assertError(() -> facade.registerWaiting(USER, CAMPAIGN), ErrorType.COUPON_ADMISSION_UNAVAILABLE);
            admission.open();
            assertError(() -> facade.registerWaiting(USER, CAMPAIGN + 1), ErrorType.COUPON_ADMISSION_UNAVAILABLE);

            verify(campaignRepository, never()).findById(anyLong());
            verifyNoInteractions(admissionRepository);
            assertThat(admission.status()).isTrue();
        }

        @DisplayName("캠페인 없음 404·비활성 409·품절 409 는 register 전에 끝난다.")
        @Test
        void campaignRejections() {
            CouponCampaignModel inactive = campaign(CouponCampaignStatus.INACTIVE, 0, 5);
            CouponCampaignModel soldOut = campaign(CouponCampaignStatus.ACTIVE, 5, 5);
            when(campaignRepository.findById(CAMPAIGN)).thenReturn(Optional.empty(), Optional.of(inactive), Optional.of(soldOut));

            assertError(() -> facade.registerWaiting(USER, CAMPAIGN), ErrorType.COUPON_CAMPAIGN_NOT_FOUND);
            assertError(() -> facade.registerWaiting(USER, CAMPAIGN), ErrorType.COUPON_CAMPAIGN_INACTIVE);
            assertError(() -> facade.registerWaiting(USER, CAMPAIGN), ErrorType.COUPON_CAMPAIGN_SOLD_OUT);
            verifyNoInteractions(admissionRepository);
        }

        @DisplayName("CREATED 만 created=true 이고, 재등록·회수 전 만료 안내는 false 다.")
        @Test
        void createdOrExisting() {
            when(admissionRepository.register(eq(CAMPAIGN), eq(USER), anyString())).thenReturn(
                new Registration(RegistrationOutcome.CREATED, snapshot(CouponWaitingState.WAITING)),
                new Registration(RegistrationOutcome.EXISTING, snapshot(CouponWaitingState.WAITING)),
                new Registration(RegistrationOutcome.EXISTING, snapshot(CouponWaitingState.EXPIRED)));

            assertAll(
                () -> assertThat(facade.registerWaiting(USER, CAMPAIGN)).isEqualTo(new CouponWaitingResult(true, snapshot(CouponWaitingState.WAITING))),
                () -> assertThat(facade.registerWaiting(USER, CAMPAIGN)).isEqualTo(new CouponWaitingResult(false, snapshot(CouponWaitingState.WAITING))),
                () -> assertThat(facade.registerWaiting(USER, CAMPAIGN)).isEqualTo(new CouponWaitingResult(false, snapshot(CouponWaitingState.EXPIRED)))
            );
        }

        @DisplayName("Redis ISSUED 는 DB 를 한 번 다시 읽어 기존 쿠폰이면 ISSUED·couponId, 없으면 500·닫힘이다.")
        @Test
        void issuedRechecksDatabase() {
            when(admissionRepository.register(eq(CAMPAIGN), eq(USER), anyString()))
                .thenReturn(new Registration(RegistrationOutcome.EXISTING, snapshot(CouponWaitingState.ISSUED)));
            when(queryRepository.findOwned(USER, CAMPAIGN)).thenReturn(Optional.empty(), Optional.of(owned()), Optional.empty(), Optional.empty());

            assertThat(facade.registerWaiting(USER, CAMPAIGN)).isEqualTo(new CouponWaitingResult(false, issuedSnapshot(WAITING_ID)));
            assertError(() -> facade.registerWaiting(USER, CAMPAIGN), ErrorType.INTERNAL_ERROR);
            assertThat(admission.status()).isFalse();
            verify(queryRepository, times(4)).findOwned(USER, CAMPAIGN);
        }
    }

    @Nested
    @DisplayName("getWaiting")
    class Get {

        @DisplayName("기존 쿠폰이 먼저이고, 운영 ID 불일치는 Redis 없이 503, 닫힘만으로는 막지 않는다.")
        @Test
        void storedThenOperatingOnly() {
            when(queryRepository.findOwned(USER, CAMPAIGN)).thenReturn(Optional.of(owned()), Optional.empty());
            when(queryRepository.findOwned(USER, CAMPAIGN + 1)).thenReturn(Optional.empty());
            when(admissionRepository.find(CAMPAIGN, USER)).thenReturn(Optional.of(snapshot(CouponWaitingState.READY)));
            admission.close();

            assertThat(facade.getWaiting(USER, CAMPAIGN)).isEqualTo(new CouponWaitingResult(false, issuedSnapshot(null)));
            assertError(() -> facade.getWaiting(USER, CAMPAIGN + 1), ErrorType.COUPON_ADMISSION_UNAVAILABLE);
            assertThat(facade.getWaiting(USER, CAMPAIGN)).isEqualTo(new CouponWaitingResult(false, snapshot(CouponWaitingState.READY)));
            verify(admissionRepository, times(1)).find(anyLong(), anyLong());
        }

        @DisplayName("항목이 없으면 404, Redis ISSUED 는 DB 를 다시 읽고, 조회 실패는 닫고 503 이다.")
        @Test
        void notFoundIssuedAndFailure() {
            when(admissionRepository.find(CAMPAIGN, USER)).thenReturn(Optional.empty(), Optional.of(snapshot(CouponWaitingState.ISSUED)))
                .thenThrow(new QueryTimeoutException("timeout(대역)"));
            when(queryRepository.findOwned(USER, CAMPAIGN)).thenReturn(Optional.empty(), Optional.empty(), Optional.of(owned()), Optional.empty());

            assertError(() -> facade.getWaiting(USER, CAMPAIGN), ErrorType.COUPON_WAITING_NOT_FOUND);
            assertThat(facade.getWaiting(USER, CAMPAIGN)).isEqualTo(new CouponWaitingResult(false, issuedSnapshot(WAITING_ID)));
            assertThat(admission.status()).isTrue();
            assertError(() -> facade.getWaiting(USER, CAMPAIGN), ErrorType.COUPON_ADMISSION_UNAVAILABLE);
            assertThat(admission.status()).isFalse();
        }
    }

    @Nested
    @DisplayName("issue")
    class Issue {

        @DisplayName("기존 쿠폰은 헤더 없이도 기존 결과다.")
        @Test
        void storedWithoutHeader() {
            when(queryRepository.findOwned(USER, CAMPAIGN)).thenReturn(Optional.of(owned()));

            assertAll(
                () -> assertThat(facade.issue(USER, CAMPAIGN, null).result().couponId()).isEqualTo(COUPON_ID),
                () -> assertThat(facade.issue(USER, CAMPAIGN, null).result().created()).isFalse()
            );
            verifyNoInteractions(admissionRepository);
        }

        @DisplayName("기존 쿠폰이 없으면 헤더 누락·공백은 400 이고 Redis 를 부르지 않는다.")
        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "  "})
        void headerRequired(String waitingId) {
            assertError(() -> facade.issue(USER, CAMPAIGN, waitingId), ErrorType.COUPON_WAITING_ID_REQUIRED);
            verifyNoInteractions(admissionRepository);
            verify(campaignRepository, never()).reserveOne(any(), any());
        }

        @DisplayName("헤더가 있어도 닫힘·운영 ID 불일치는 start 전에 503 이다.")
        @Test
        void closedOrOtherCampaign() {
            when(queryRepository.findOwned(USER, CAMPAIGN + 1)).thenReturn(Optional.empty());
            assertError(() -> facade.issue(USER, CAMPAIGN + 1, WAITING_ID), ErrorType.COUPON_ADMISSION_UNAVAILABLE);
            admission.close();
            assertError(() -> facade.issue(USER, CAMPAIGN, WAITING_ID), ErrorType.COUPON_ADMISSION_UNAVAILABLE);
            verifyNoInteractions(admissionRepository);
        }

        @DisplayName("start 의 정상 거절은 그대로 전파되고 DB 쓰기·finish 가 없다.")
        @ParameterizedTest
        @EnumSource(value = StartOutcome.class, names = {"NOT_FOUND", "NOT_READY", "EXPIRED", "ALREADY_USED"})
        void startRejections(StartOutcome outcome) {
            when(admissionRepository.start(CAMPAIGN, USER, WAITING_ID)).thenReturn(new Start(outcome, null));

            assertThatThrownBy(() -> facade.issue(USER, CAMPAIGN, WAITING_ID)).isInstanceOf(CoreException.class);
            verify(campaignRepository, never()).reserveOne(any(), any());
            verify(admissionRepository, never()).finish(any(), any(), any(), any());
            assertThat(admission.status()).isTrue();
        }

        @DisplayName("PROCESSING 은 DB 를 한 번 다시 읽어 쿠폰이 있으면 기존 결과, 없으면 진행 중 결과다. DB 쓰기·finish 는 없다.")
        @Test
        void processingRechecks() {
            Start processing = new Start(StartOutcome.PROCESSING, snapshot(CouponWaitingState.PROCESSING));
            when(admissionRepository.start(CAMPAIGN, USER, WAITING_ID)).thenReturn(processing);
            when(queryRepository.findOwned(USER, CAMPAIGN)).thenReturn(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(owned()));

            CouponIssueOutcome inProgress = facade.issue(USER, CAMPAIGN, WAITING_ID);
            CouponIssueOutcome committedMeanwhile = facade.issue(USER, CAMPAIGN, WAITING_ID);

            assertAll(
                () -> assertThat(inProgress).isEqualTo(new CouponIssueOutcome(null, snapshot(CouponWaitingState.PROCESSING))),
                () -> assertThat(committedMeanwhile.result().couponId()).isEqualTo(COUPON_ID),
                () -> assertThat(committedMeanwhile.result().created()).isFalse(),
                () -> verify(queryRepository, times(4)).findOwned(USER, CAMPAIGN),
                () -> verify(campaignRepository, never()).reserveOne(any(), any()),
                () -> verify(admissionRepository, never()).finish(any(), any(), any(), any())
            );
        }

        @DisplayName("ISSUED 는 DB 를 한 번 다시 읽어 기존 결과이고, 그래도 없으면 새 발급 없이 500·닫힘이다.")
        @Test
        void issuedRechecks() {
            when(admissionRepository.start(CAMPAIGN, USER, WAITING_ID))
                .thenReturn(new Start(StartOutcome.ISSUED, new CouponWaitingSnapshot(CAMPAIGN, WAITING_ID, CouponWaitingState.ISSUED, null, null, null)));
            when(queryRepository.findOwned(USER, CAMPAIGN)).thenReturn(Optional.empty(), Optional.of(owned()), Optional.empty(), Optional.empty());

            assertThat(facade.issue(USER, CAMPAIGN, WAITING_ID).result().couponId()).isEqualTo(COUPON_ID);
            assertError(() -> facade.issue(USER, CAMPAIGN, WAITING_ID), ErrorType.INTERNAL_ERROR);
            assertAll(
                () -> assertThat(admission.status()).isFalse(),
                () -> verify(campaignRepository, never()).reserveOne(any(), any()),
                () -> verify(admissionRepository, never()).finish(any(), any(), any(), any())
            );
        }
    }

    @Nested
    @DisplayName("processAdmissions")
    class Process {

        @DisplayName("미설정·닫힘이면 DB·Redis 를 호출하지 않는다.")
        @Test
        void skippedWhenNotAdmitting() {
            CouponAdmissionService unconfigured = new CouponAdmissionService(admissionRepository, new CouponAdmissionProperties(3),
                new CouponWaitingProperties(null, Duration.ofSeconds(1), Duration.ofSeconds(30)));
            CouponFacade unconfiguredFacade = new CouponFacade(tm, queryRepository, campaignRepository, couponRepository,
                new ConstraintViolationClassifier(), unconfigured);
            admission.close();

            assertAll(
                () -> assertThat(unconfiguredFacade.processAdmissions()).isEmpty(),
                () -> assertThat(facade.processAdmissions()).isEmpty()
            );
            assertThat(tm.begun()).isZero();
            verifyNoInteractions(admissionRepository, campaignRepository);
        }

        @DisplayName("DB 상태를 값으로 읽은 뒤 TX 밖에서 Redis 를 부른다. 활성·미품절만 승격 가능, 품절은 표시, 없음·비활성은 회수만이다.")
        @Test
        void soldOutAndCanAdmitFromDatabase() {
            List<String> observed = new ArrayList<>();
            when(admissionRepository.processAdmissions(eq(CAMPAIGN), eq(3), eq(30_000L), anyBoolean(), anyBoolean())).thenAnswer(invocation -> {
                observed.add(invocation.getArgument(3) + "/" + invocation.getArgument(4)
                    + " sync=" + TransactionSynchronizationManager.isSynchronizationActive());
                return new AdmissionProgress(0, 0);
            });
            CouponCampaignModel open = campaign(CouponCampaignStatus.ACTIVE, 4, 5);
            CouponCampaignModel soldOut = campaign(CouponCampaignStatus.ACTIVE, 5, 5);
            CouponCampaignModel inactive = campaign(CouponCampaignStatus.INACTIVE, 0, 5);
            when(campaignRepository.findById(CAMPAIGN)).thenReturn(Optional.of(open), Optional.of(soldOut), Optional.of(inactive), Optional.empty());

            for (int i = 0; i < 4; i++) {
                assertThat(facade.processAdmissions()).contains(new AdmissionProgress(0, 0));
            }

            assertThat(observed).containsExactly("false/true sync=false", "true/false sync=false", "false/false sync=false", "false/false sync=false");
            verify(campaignRepository, never()).reserveOne(any(), any());
            verifyNoInteractions(couponRepository);
        }

        @DisplayName("Redis 실패는 닫고 503 이며, 다음 호출은 DB·Redis 를 부르지 않는다.")
        @Test
        void failureClosesThenSkips() {
            when(admissionRepository.processAdmissions(eq(CAMPAIGN), anyInt(), anyLong(), anyBoolean(), anyBoolean()))
                .thenThrow(new QueryTimeoutException("timeout(대역)"));

            assertError(() -> facade.processAdmissions(), ErrorType.COUPON_ADMISSION_UNAVAILABLE);
            assertThat(facade.processAdmissions()).isEmpty();
            verify(campaignRepository, times(1)).findById(CAMPAIGN);
            verify(admissionRepository, times(1)).processAdmissions(any(), anyInt(), anyLong(), anyBoolean(), anyBoolean());
        }
    }
}
