package com.loopers.application.coupon;

import com.loopers.config.CouponAdmissionProperties;
import com.loopers.config.CouponWaitingProperties;
import com.loopers.domain.coupon.CouponAdmissionRepository;
import com.loopers.domain.coupon.CouponAdmissionRepository.Acquisition;
import com.loopers.domain.coupon.CouponAdmissionRepository.AdmissionProgress;
import com.loopers.domain.coupon.CouponAdmissionRepository.FinishOutcome;
import com.loopers.domain.coupon.CouponAdmissionRepository.Registration;
import com.loopers.domain.coupon.CouponAdmissionRepository.RegistrationOutcome;
import com.loopers.domain.coupon.CouponAdmissionRepository.Start;
import com.loopers.domain.coupon.CouponAdmissionRepository.StartOutcome;
import com.loopers.domain.coupon.CouponWaitingSnapshot;
import com.loopers.domain.coupon.CouponWaitingState;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisSystemException;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 수용 서비스의 분기(설계 §7·§8.4, Q8·Q14 의 서비스 부분). 저장소는 대역이며 Redis 의 실제 timeout·오류 재현이 아니다.
 * 실제 Redis 경로는 CouponAdmissionRedisIntegrationTest 가 본다.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("수용 서비스는 기본 닫힘이고, FULL 과 오류·불명을 구분하며, 오류·불명·첫 반환 이상에서만 닫는다.")
class CouponAdmissionServiceTest {

    private static final String TOKEN = "token-a";
    private static final Long CAMPAIGN = 7L;
    private static final Long USER = 11L;
    private static final String WAITING_ID = "w-1";
    private static final CouponWaitingProperties WAITING = new CouponWaitingProperties(CAMPAIGN, Duration.ofSeconds(1), Duration.ofSeconds(30));

    private CouponAdmissionRepository repository;
    private CouponAdmissionService service;

    @BeforeEach
    void setUp() {
        repository = mock(CouponAdmissionRepository.class);
        service = new CouponAdmissionService(repository, new CouponAdmissionProperties(3), WAITING);
    }

    private static ErrorType errorTypeOf(Throwable thrown) {
        return ((CoreException) thrown).getErrorType();
    }

    @Nested
    @DisplayName("열림·닫힘")
    class OpenClose {

        @DisplayName("시작 상태는 닫힘이고 닫힌 상태의 acquire 는 저장소를 호출하지 않고 503 이다.")
        @Test
        void closedByDefault() {
            assertThat(service.status()).isFalse();

            assertThatThrownBy(() -> service.acquire(TOKEN))
                .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.COUPON_ADMISSION_UNAVAILABLE));
            verifyNoInteractions(repository);
        }

        @DisplayName("open 뒤 close 하면 다시 저장소를 호출하지 않는다. close·open 은 현재 값을 돌려주고 반복할 수 있다.")
        @Test
        void explicitOpenAndClose() {
            assertAll(
                () -> assertThat(service.open()).isTrue(),
                () -> assertThat(service.open()).isTrue(),
                () -> assertThat(service.close()).isFalse(),
                () -> assertThat(service.close()).isFalse()
            );

            assertThatThrownBy(() -> service.acquire(TOKEN)).isInstanceOf(CoreException.class);
            verifyNoInteractions(repository);
        }
    }

    @Nested
    @DisplayName("획득")
    class Acquire {

        @BeforeEach
        void open() {
            service.open();
        }

        @DisplayName("ACQUIRED 면 설정 C 로 호출하고 예외 없이 끝나며 열림을 유지한다.")
        @Test
        void acquired() {
            when(repository.tryAcquire(TOKEN, 3)).thenReturn(Acquisition.ACQUIRED);

            service.acquire(TOKEN);

            verify(repository).tryAcquire(TOKEN, 3);
            assertThat(service.status()).isTrue();
        }

        @DisplayName("FULL 은 429 이고 수용을 닫지 않는다.")
        @Test
        void fullKeepsOpen() {
            when(repository.tryAcquire(TOKEN, 3)).thenReturn(Acquisition.FULL);

            assertThatThrownBy(() -> service.acquire(TOKEN))
                .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.COUPON_ADMISSION_FULL));
            assertThat(service.status()).isTrue();
        }

        @DisplayName("저장소 예외(timeout·Redis 오류·비정상 응답)는 503·닫힘이고 token·단계·불명을 로그로 남긴다.")
        @ParameterizedTest
        @MethodSource("com.loopers.application.coupon.CouponAdmissionServiceTest#repositoryFailures")
        void failureCloses(RuntimeException failure, CapturedOutput output) {
            when(repository.tryAcquire(anyString(), anyInt())).thenThrow(failure);

            assertThatThrownBy(() -> service.acquire(TOKEN))
                .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.COUPON_ADMISSION_UNAVAILABLE));
            assertAll(
                () -> assertThat(service.status()).isFalse(),
                () -> assertThat(output).contains("stage = 획득", "token = " + TOKEN, "획득 여부 불명")
            );
        }

        @DisplayName("결과 null 은 FULL 이 아니라 불명으로 보고 503·닫힘이다.")
        @Test
        void nullResultCloses(CapturedOutput output) {
            when(repository.tryAcquire(TOKEN, 3)).thenReturn(null);

            assertThatThrownBy(() -> service.acquire(TOKEN))
                .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.COUPON_ADMISSION_UNAVAILABLE));
            assertAll(
                () -> assertThat(service.status()).isFalse(),
                () -> assertThat(output).contains("token = " + TOKEN, "획득 여부 불명")
            );
        }

        @DisplayName("오류로 닫힌 뒤의 acquire 는 저장소를 다시 호출하지 않는다.")
        @Test
        void closedAfterFailureSkipsRepository() {
            when(repository.tryAcquire(TOKEN, 3)).thenThrow(new QueryTimeoutException("timeout"));
            assertThatThrownBy(() -> service.acquire(TOKEN)).isInstanceOf(CoreException.class);

            assertThatThrownBy(() -> service.acquire("token-b")).isInstanceOf(CoreException.class);

            verify(repository).tryAcquire(TOKEN, 3);
            verify(repository, never()).tryAcquire("token-b", 3);
        }
    }

    static Stream<RuntimeException> repositoryFailures() {
        return Stream.of(
            new QueryTimeoutException("Redis command timed out"),
            new RedisSystemException("WRONGTYPE", new RuntimeException("WRONGTYPE")),
            new IllegalStateException("수용 자리 획득 응답이 1/0 이 아닙니다.")
        );
    }

    @Nested
    @DisplayName("정상 실행의 첫 반환")
    class Release {

        @BeforeEach
        void open() {
            service.open();
        }

        @DisplayName("1 이면 열림을 유지하고 로그를 남기지 않는다.")
        @Test
        void removedOne(CapturedOutput output) {
            when(repository.release(TOKEN)).thenReturn(1L);

            assertThat(service.release(TOKEN)).isTrue();

            assertThat(service.status()).isTrue();
            assertThat(output).doesNotContain("수용을 닫습니다");
        }

        @DisplayName("0 은 있어야 할 점유가 없다는 이상 신호이므로 예외 없이 로그·닫힘이다.")
        @Test
        void removedZeroCloses(CapturedOutput output) {
            when(repository.release(TOKEN)).thenReturn(0L);

            assertThat(service.release(TOKEN)).isFalse();

            assertAll(
                () -> assertThat(service.status()).isFalse(),
                () -> assertThat(output).contains("stage = 반환", "token = " + TOKEN, "점유 없음(removed 0)")
            );
        }

        @DisplayName("반환 예외는 호출자에게 던지지 않고 로그·닫힘이다. 확정된 업무 결과를 덮어쓰지 않기 위함이다.")
        @ParameterizedTest
        @MethodSource("com.loopers.application.coupon.CouponAdmissionServiceTest#repositoryFailures")
        void failureClosesWithoutThrowing(RuntimeException failure, CapturedOutput output) {
            when(repository.release(TOKEN)).thenThrow(failure);

            assertThat(service.release(TOKEN)).isFalse();

            assertAll(
                () -> assertThat(service.status()).isFalse(),
                () -> assertThat(output).contains("stage = 반환", "token = " + TOKEN, "반환 여부 불명")
            );
        }

        @DisplayName("뒤늦은 정상 반환 1 은 닫힌 수용을 다시 열지 않는다.")
        @Test
        void lateSuccessDoesNotReopen() {
            when(repository.release(TOKEN)).thenReturn(0L, 1L);
            service.release(TOKEN);

            service.release(TOKEN);

            assertThat(service.status()).isFalse();
        }
    }

    @Nested
    @DisplayName("cleanup")
    class Cleanup {

        @DisplayName("열린 상태에서는 409 이고 저장소를 호출하지 않는다.")
        @Test
        void rejectedWhenOpen() {
            service.open();

            assertThatThrownBy(() -> service.cleanup(List.of(TOKEN)))
                .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.COUPON_ADMISSION_OPERATION_REJECTED));
            verifyNoInteractions(repository);
            assertThat(service.status()).isTrue();
        }

        @DisplayName("열린 상태 거절이 입력 검증보다 먼저다.")
        @Test
        void openCheckedBeforeInput() {
            service.open();

            assertThatThrownBy(() -> service.cleanup(List.of()))
                .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.COUPON_ADMISSION_OPERATION_REJECTED));
        }

        @DisplayName("null·빈 목록·null/빈/공백 token 은 400 INVALID_REQUEST 이고 저장소를 호출하지 않는다.")
        @ParameterizedTest
        @MethodSource("com.loopers.application.coupon.CouponAdmissionServiceTest#invalidTokens")
        void invalidInput(List<String> tokens) {
            assertThatThrownBy(() -> service.cleanup(tokens))
                .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.INVALID_REQUEST));
            verifyNoInteractions(repository);
        }

        @DisplayName("닫힌 상태에서 지정 token 만 제거하고 제거 수를 합한다. 이미 없는 token 의 0 은 정상이며 닫힘을 유지한다.")
        @Test
        void removesOnlyGivenTokens() {
            when(repository.release("a")).thenReturn(1L);
            when(repository.release("b")).thenReturn(0L);

            assertThat(service.cleanup(List.of("a", "b"))).isEqualTo(1L);

            verify(repository).release("a");
            verify(repository).release("b");
            verifyNoMoreInteractions(repository);
            assertThat(service.status()).isFalse();
        }

        @DisplayName("반복 cleanup 의 0 은 오류가 아니다.")
        @Test
        void repeatedCleanupIsSafe() {
            when(repository.release("a")).thenReturn(1L, 0L);

            assertAll(
                () -> assertThat(service.cleanup(List.of("a"))).isEqualTo(1L),
                () -> assertThat(service.cleanup(List.of("a"))).isZero()
            );
        }

        @DisplayName("도중 Redis 실패는 503·닫힘 유지이고, 앞선 제거는 되돌리지 않으며 뒤 token 은 시도하지 않는다.")
        @Test
        void failureMidway(CapturedOutput output) {
            when(repository.release("a")).thenReturn(1L);
            when(repository.release("b")).thenThrow(new QueryTimeoutException("timeout"));

            assertThatThrownBy(() -> service.cleanup(List.of("a", "b", "c")))
                .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.COUPON_ADMISSION_UNAVAILABLE));

            verify(repository, never()).release("c");
            assertAll(
                () -> assertThat(service.status()).isFalse(),
                () -> assertThat(output).contains("stage = 정리", "token = b", "앞선 token 제거 수 1")
            );
        }
    }

    static Stream<List<String>> invalidTokens() {
        return Stream.of(
            null,
            Collections.emptyList(),
            Arrays.asList("a", null),
            List.of("a", ""),
            List.of("  ")
        );
    }

    private static CouponWaitingSnapshot snapshot(CouponWaitingState status) {
        return new CouponWaitingSnapshot(CAMPAIGN, WAITING_ID, status, status == CouponWaitingState.WAITING ? 1L : null, null, null);
    }

    @Nested
    @DisplayName("대기실 설정·진입 조건")
    class WaitingEntry {

        @DisplayName("운영 캠페인이 미설정이면 닫혀 있고 open 은 409, 조회·입장 처리는 저장소를 부르지 않는다.")
        @Test
        void unconfiguredStaysClosed() {
            CouponAdmissionService unconfigured = new CouponAdmissionService(repository, new CouponAdmissionProperties(3),
                new CouponWaitingProperties(null, Duration.ofSeconds(1), Duration.ofSeconds(30)));

            assertAll(
                () -> assertThat(unconfigured.status()).isFalse(),
                () -> assertThatThrownBy(unconfigured::open)
                    .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.COUPON_ADMISSION_OPERATION_REJECTED)),
                () -> assertThat(unconfigured.status()).isFalse(),
                () -> assertThatThrownBy(() -> unconfigured.requireOperating(CAMPAIGN))
                    .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.COUPON_ADMISSION_UNAVAILABLE)),
                () -> assertThat(unconfigured.admittingCampaignId()).isEmpty(),
                () -> assertThat(unconfigured.processAdmissions(false, true)).isEmpty()
            );
            verifyNoInteractions(repository);
        }

        @DisplayName("설정돼도 기본 닫힘이다. 조회는 운영 ID 만 보고, 신규 진입은 열림까지 본다. 다른 캠페인은 열려 있어도 503 이다.")
        @Test
        void operatingAndAdmittingChecks() {
            assertAll(
                () -> assertThat(service.status()).isFalse(),
                () -> service.requireOperating(CAMPAIGN),
                () -> assertThatThrownBy(() -> service.requireAdmitting(CAMPAIGN))
                    .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.COUPON_ADMISSION_UNAVAILABLE)),
                () -> assertThat(service.admittingCampaignId()).isEmpty()
            );
            service.open();
            assertAll(
                () -> service.requireAdmitting(CAMPAIGN),
                () -> assertThat(service.admittingCampaignId()).contains(CAMPAIGN),
                () -> assertThatThrownBy(() -> service.requireOperating(CAMPAIGN + 1))
                    .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.COUPON_ADMISSION_UNAVAILABLE)),
                () -> assertThatThrownBy(() -> service.requireAdmitting(CAMPAIGN + 1))
                    .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.COUPON_ADMISSION_UNAVAILABLE)),
                () -> assertThat(service.status()).as("운영 ID 불일치는 장애가 아니다").isTrue()
            );
            verifyNoInteractions(repository);
        }
    }

    static Stream<RuntimeException> waitingFailures() {
        return Stream.of(
            new QueryTimeoutException("Redis command timed out"),
            new RedisSystemException("ERR", new RuntimeException("ERR")),
            new IllegalStateException("register Lua 응답 형식이 계약과 다릅니다.")
        );
    }

    @Nested
    @DisplayName("대기실 결과 해석")
    class WaitingResults {

        @BeforeEach
        void open() {
            service.open();
        }

        private void assertUnavailableAndClosed(Runnable call) {
            assertThatThrownBy(call::run).satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.COUPON_ADMISSION_UNAVAILABLE));
            assertThat(service.status()).isFalse();
        }

        @DisplayName("register: CREATED/EXISTING 은 그대로, Redis 품절은 409 이며 둘 다 수용을 유지한다. 후보 ID 는 서버가 만든다.")
        @Test
        void registerNormalResults() {
            Registration created = new Registration(RegistrationOutcome.CREATED, snapshot(CouponWaitingState.WAITING));
            Registration expired = new Registration(RegistrationOutcome.EXISTING, snapshot(CouponWaitingState.EXPIRED));
            when(repository.register(eq(CAMPAIGN), eq(USER), anyString()))
                .thenReturn(created, expired, new Registration(RegistrationOutcome.SOLD_OUT, null));

            assertAll(
                () -> assertThat(service.register(CAMPAIGN, USER)).isEqualTo(created),
                () -> assertThat(service.register(CAMPAIGN, USER)).isEqualTo(expired),
                () -> assertThatThrownBy(() -> service.register(CAMPAIGN, USER))
                    .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(ErrorType.COUPON_CAMPAIGN_SOLD_OUT)),
                () -> assertThat(service.status()).isTrue()
            );
            ArgumentCaptor<String> candidate = ArgumentCaptor.forClass(String.class);
            verify(repository, times(3)).register(eq(CAMPAIGN), eq(USER), candidate.capture());
            assertThat(candidate.getAllValues()).allSatisfy(id -> assertThat(id).isNotBlank()).doesNotHaveDuplicates();
        }

        @DisplayName("register: 실패·INVARIANT·응답 없음은 닫고 503 이다.")
        @ParameterizedTest
        @MethodSource("com.loopers.application.coupon.CouponAdmissionServiceTest#waitingFailures")
        void registerFailureCloses(RuntimeException failure) {
            when(repository.register(eq(CAMPAIGN), eq(USER), anyString())).thenThrow(failure);
            assertUnavailableAndClosed(() -> service.register(CAMPAIGN, USER));
        }

        @DisplayName("register·start 의 INVARIANT_ERROR 와 null 응답도 닫고 503 이다.")
        @Test
        void invariantOrNullCloses() {
            when(repository.register(eq(CAMPAIGN), eq(USER), anyString())).thenReturn(new Registration(RegistrationOutcome.INVARIANT_ERROR, null));
            assertUnavailableAndClosed(() -> service.register(CAMPAIGN, USER));
            service.open();
            when(repository.register(eq(CAMPAIGN), eq(USER), anyString())).thenReturn(null);
            assertUnavailableAndClosed(() -> service.register(CAMPAIGN, USER));
            service.open();
            when(repository.start(CAMPAIGN, USER, WAITING_ID)).thenReturn(new Start(StartOutcome.INVARIANT_ERROR, null));
            assertUnavailableAndClosed(() -> service.start(CAMPAIGN, USER, WAITING_ID));
        }

        @DisplayName("find: 없음·정상 상태는 그대로이고 수용을 유지한다. 조회 실패도 관찰한 Redis 오류라 닫고 503 이다.")
        @Test
        void findResults() {
            when(repository.find(CAMPAIGN, USER)).thenReturn(Optional.empty(), Optional.of(snapshot(CouponWaitingState.SOLD_OUT)))
                .thenThrow(new QueryTimeoutException("timeout(대역)"));

            assertThat(service.find(CAMPAIGN, USER)).isEmpty();
            assertThat(service.find(CAMPAIGN, USER)).contains(snapshot(CouponWaitingState.SOLD_OUT));
            assertThat(service.status()).isTrue();
            assertUnavailableAndClosed(() -> service.find(CAMPAIGN, USER));
        }

        @DisplayName("start: STARTED·PROCESSING·ISSUED 는 돌려주고, NOT_FOUND 404·NOT_READY/EXPIRED/ALREADY_USED 409 는 수용을 유지한다.")
        @Test
        void startNormalResults() {
            Start processing = new Start(StartOutcome.PROCESSING, snapshot(CouponWaitingState.PROCESSING));
            when(repository.start(CAMPAIGN, USER, WAITING_ID)).thenReturn(
                new Start(StartOutcome.STARTED, null), processing,
                new Start(StartOutcome.NOT_FOUND, null), new Start(StartOutcome.NOT_READY, null),
                new Start(StartOutcome.EXPIRED, null), new Start(StartOutcome.ALREADY_USED, null));

            assertAll(
                () -> assertThat(service.start(CAMPAIGN, USER, WAITING_ID).outcome()).isEqualTo(StartOutcome.STARTED),
                () -> assertThat(service.start(CAMPAIGN, USER, WAITING_ID)).isEqualTo(processing),
                () -> assertStartRejected(ErrorType.COUPON_WAITING_NOT_FOUND),
                () -> assertStartRejected(ErrorType.COUPON_WAITING_NOT_READY),
                () -> assertStartRejected(ErrorType.COUPON_WAITING_EXPIRED),
                () -> assertStartRejected(ErrorType.COUPON_WAITING_ALREADY_USED),
                () -> assertThat(service.status()).isTrue()
            );
        }

        private void assertStartRejected(ErrorType expected) {
            assertThatThrownBy(() -> service.start(CAMPAIGN, USER, WAITING_ID))
                .satisfies(e -> assertThat(errorTypeOf(e)).isEqualTo(expected));
        }

        @DisplayName("start: 실패는 시작 여부 불명이라 닫고 503 이다.")
        @ParameterizedTest
        @MethodSource("com.loopers.application.coupon.CouponAdmissionServiceTest#waitingFailures")
        void startFailureCloses(RuntimeException failure) {
            when(repository.start(CAMPAIGN, USER, WAITING_ID)).thenThrow(failure);
            assertUnavailableAndClosed(() -> service.start(CAMPAIGN, USER, WAITING_ID));
        }

        @DisplayName("finish: APPLIED 는 유지, UNCHANGED 는 waitingId 포함 WARN 만 남기고 유지한다. 예외를 던지지 않는다.")
        @Test
        void finishAppliedOrUnchangedKeepsOpen(CapturedOutput output) {
            when(repository.finish(CAMPAIGN, USER, WAITING_ID, CouponWaitingState.ISSUED))
                .thenReturn(FinishOutcome.APPLIED, FinishOutcome.UNCHANGED);

            assertAll(
                () -> assertThat(service.finish(CAMPAIGN, USER, WAITING_ID, CouponWaitingState.ISSUED)).isTrue(),
                () -> assertThat(service.finish(CAMPAIGN, USER, WAITING_ID, CouponWaitingState.ISSUED)).isFalse(),
                () -> assertThat(service.status()).isTrue(),
                () -> assertThat(output).contains("WARN", "waitingId = " + WAITING_ID).doesNotContain("수용을 닫습니다")
            );
        }

        @DisplayName("finish: INVARIANT·실패·응답 없음은 예외 없이 닫는다.")
        @Test
        void finishFailureClosesWithoutThrowing(CapturedOutput output) {
            when(repository.finish(CAMPAIGN, USER, WAITING_ID, CouponWaitingState.FAILED))
                .thenReturn(FinishOutcome.INVARIANT_ERROR, (FinishOutcome) null)
                .thenThrow(new QueryTimeoutException("timeout(대역)"));

            for (int i = 0; i < 3; i++) {
                service.open();
                assertThat(service.finish(CAMPAIGN, USER, WAITING_ID, CouponWaitingState.FAILED)).isFalse();
                assertThat(service.status()).isFalse();
            }
            assertThat(output).contains("stage = 종료", "waitingId = " + WAITING_ID);
        }

        @DisplayName("close 뒤에도 진행 중 실행의 finish 는 저장소로 보낸다.")
        @Test
        void finishAfterClose() {
            when(repository.finish(CAMPAIGN, USER, WAITING_ID, CouponWaitingState.ISSUED)).thenReturn(FinishOutcome.APPLIED);
            service.close();

            assertThat(service.finish(CAMPAIGN, USER, WAITING_ID, CouponWaitingState.ISSUED)).isTrue();
            verify(repository).finish(CAMPAIGN, USER, WAITING_ID, CouponWaitingState.ISSUED);
        }

        @DisplayName("processAdmissions: 운영 캠페인·C·입장 기한 밀리초로 호출하고, 닫힘이면 부르지 않으며, 실패는 닫고 503 이다.")
        @Test
        void processAdmissions() {
            when(repository.processAdmissions(CAMPAIGN, 3, 30_000L, true, false)).thenReturn(new AdmissionProgress(1, 0))
                .thenThrow(new QueryTimeoutException("timeout(대역)"));

            assertThat(service.processAdmissions(true, false)).contains(new AdmissionProgress(1, 0));
            assertUnavailableAndClosed(() -> service.processAdmissions(true, false));
            assertThat(service.processAdmissions(true, false)).as("닫힘").isEmpty();
            verify(repository, times(2)).processAdmissions(CAMPAIGN, 3, 30_000L, true, false);
        }
    }
}
