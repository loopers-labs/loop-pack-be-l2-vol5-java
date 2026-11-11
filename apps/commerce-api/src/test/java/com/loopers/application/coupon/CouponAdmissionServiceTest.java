package com.loopers.application.coupon;

import com.loopers.config.CouponAdmissionProperties;
import com.loopers.domain.coupon.CouponAdmissionRepository;
import com.loopers.domain.coupon.CouponAdmissionRepository.Acquisition;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisSystemException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    private CouponAdmissionRepository repository;
    private CouponAdmissionService service;

    @BeforeEach
    void setUp() {
        repository = mock(CouponAdmissionRepository.class);
        service = new CouponAdmissionService(repository, new CouponAdmissionProperties(3));
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

            service.release(TOKEN);

            assertThat(service.status()).isTrue();
            assertThat(output).doesNotContain("수용을 닫습니다");
        }

        @DisplayName("0 은 있어야 할 점유가 없다는 이상 신호이므로 예외 없이 로그·닫힘이다.")
        @Test
        void removedZeroCloses(CapturedOutput output) {
            when(repository.release(TOKEN)).thenReturn(0L);

            service.release(TOKEN);

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

            service.release(TOKEN);

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
}
