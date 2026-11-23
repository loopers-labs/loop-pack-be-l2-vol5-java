package com.loopers.infrastructure.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;

/** 업무 실패·실패 기록·정리 실패가 겹칠 때 어느 오류가 남는지 실제 자원 없이 확인한다. */
@DisplayName("W10 시나리오 실패·정리 조합 검증")
class CheckoutIncidentLifecycleTest {

    @DisplayName("업무와 정리가 함께 실패하면 업무 실패가 주 예외이고 정리 실패는 suppressed 다.")
    @Test
    void keepsPrimaryFailureOverCleanupFailure() {
        AssertionError business = new AssertionError("업무 실패");
        IllegalStateException cleanup = new IllegalStateException("정리 실패");
        List<Throwable> recorded = new ArrayList<>();

        Throwable thrown = catchThrowable(() -> CheckoutIncidentScenario.runThenRelease(
            () -> {
                throw business;
            }, recorded::add, () -> {
                throw cleanup;
            }));

        assertAll(
            () -> assertThat(thrown).isSameAs(business),
            () -> assertThat(thrown.getSuppressed()).containsExactly(cleanup),
            () -> assertThat(recorded).containsExactly(business)
        );
    }

    @DisplayName("업무가 성공하고 정리만 실패하면 정리 실패를 던진다.")
    @Test
    void failsWhenOnlyCleanupFails() {
        IllegalStateException cleanup = new IllegalStateException("정리 실패");

        Throwable thrown = catchThrowable(() -> CheckoutIncidentScenario.runThenRelease(() -> {
        }, failure -> {
        }, () -> {
            throw cleanup;
        }));

        assertThat(thrown).isSameAs(cleanup);
    }

    @DisplayName("실패 기록 자체가 실패해도 원래 오류를 잃지 않고 정리도 수행한다.")
    @Test
    void keepsPrimaryWhenRecordingFails() {
        IllegalStateException business = new IllegalStateException("준비 실패");
        IllegalStateException recordError = new IllegalStateException("기록 실패");
        boolean[] released = {false};

        Throwable thrown = catchThrowable(() -> CheckoutIncidentScenario.runThenRelease(
            () -> {
                throw business;
            }, failure -> {
                throw recordError;
            }, () -> released[0] = true));

        assertAll(
            () -> assertThat(thrown).isSameAs(business),
            () -> assertThat(thrown.getSuppressed()).containsExactly(recordError),
            () -> assertThat(released[0]).as("정리 수행").isTrue()
        );
    }

    @DisplayName("업무와 정리가 모두 성공하면 예외가 없고 실패 기록도 없다.")
    @Test
    void passesWhenBothSucceed() {
        List<Throwable> recorded = new ArrayList<>();
        boolean[] released = {false};

        assertThatCode(() -> CheckoutIncidentScenario.runThenRelease(() -> {
        }, recorded::add, () -> released[0] = true)).doesNotThrowAnyException();

        assertAll(
            () -> assertThat(recorded).isEmpty(),
            () -> assertThat(released[0]).isTrue()
        );
    }
}
