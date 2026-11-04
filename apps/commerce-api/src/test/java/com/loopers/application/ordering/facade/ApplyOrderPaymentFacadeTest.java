package com.loopers.application.ordering.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;

import com.loopers.application.ordering.result.ApplyOrderPaymentResult;
import com.loopers.application.ordering.service.ApplyOrderPaymentService;
import com.loopers.application.ordering.service.RetryOrderPaymentService;
import com.loopers.application.ordering.support.PaymentApplicationFailureException;
import com.loopers.application.pay.service.PaymentRecoveryProgressService;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ApplyOrderPaymentFacadeTest {
    private final ApplyOrderPaymentService application = mock(ApplyOrderPaymentService.class);
    private final PaymentRecoveryProgressService progress = mock(PaymentRecoveryProgressService.class);
    private final RetryOrderPaymentService retry = mock(RetryOrderPaymentService.class);
    private final ApplyOrderPaymentFacade facade = new ApplyOrderPaymentFacade(application, retry, progress);
    private final String key = UUID.randomUUID().toString();

    @ParameterizedTest
    @EnumSource(value = ApplyOrderPaymentResult.State.class, names = {"APPLIED", "APPLICATION_FAILED"})
    @DisplayName("수동 표식 저장만 실패해도 새 조회로 확인한 완료 또는 미완료 상태를 반환한다")
    void readsCurrentStateAfterMarkerFailure(ApplyOrderPaymentResult.State state) {
        arrangeMarkerFailure();
        ApplyOrderPaymentResult expected = new ApplyOrderPaymentResult(state, true);
        given(application.currentResult(key, true)).willReturn(expected);

        assertThat(facade.execute(key)).isEqualTo(expected);
    }

    @Test
    @DisplayName("표식 저장과 상태 조회가 모두 실패하면 조회 장애를 숨기지 않는다")
    void propagatesCurrentReadFailure() {
        arrangeMarkerFailure();
        RuntimeException readFailure = new IllegalStateException("상태 조회 장애");
        given(application.currentResult(key, true)).willThrow(readFailure);

        assertThatThrownBy(() -> facade.execute(key)).isSameAs(readFailure);
    }

    private void arrangeMarkerFailure() {
        PaymentRecoveryTask.Snapshot expected = PaymentRecoveryTask.create(key).snapshot();
        given(progress.capture(key)).willReturn(expected);
        given(retry.apply(key)).willThrow(new PaymentApplicationFailureException(new IllegalStateException("업무 실패"), true));
        given(progress.markApplicationFailed(expected)).willThrow(new IllegalStateException("표식 저장 실패"));
    }

    @Test
    @DisplayName("수동 업무 실패는 전달받은 당시 실행으로만 표시하고 인계 후 최신 실행을 비교값으로 다시 읽지 않는다")
    void preservesProvidedManualExecutionSnapshot() {
        PaymentRecoveryTask.Snapshot old = PaymentRecoveryTask.create(key).claim(UUID.randomUUID().toString(),
            java.time.Instant.now()).snapshot();
        given(retry.apply(key)).willThrow(new PaymentApplicationFailureException(new IllegalStateException("업무 실패"), true));
        given(application.currentResult(key, true)).willReturn(
            new ApplyOrderPaymentResult(ApplyOrderPaymentResult.State.APPLICATION_FAILED, true));
        facade.execute(key, old);
        verify(progress).markApplicationFailed(old);
        verify(progress, never()).capture(key);
    }

    @Test
    @DisplayName("commit 결과가 불명확해도 저장 상태가 완료면 실패 표시 없이 완료를 반환한다")
    void readsCompletionBeforeMarkingUncertainCommit() {
        var snapshot = PaymentRecoveryTask.create(key).snapshot();
        given(retry.apply(key)).willThrow(new PaymentApplicationFailureException(new IllegalStateException("commit 미확인"), false));
        var completed = new ApplyOrderPaymentResult(ApplyOrderPaymentResult.State.APPLIED, false);
        given(application.currentResult(key, false)).willReturn(completed);

        assertThat(facade.execute(key, snapshot)).isEqualTo(completed);
        verify(progress, never()).markApplicationFailed(snapshot);
    }

    @Test
    @DisplayName("commit 미확인의 미완료를 확인한 뒤에만 최초 실행으로 표시하고 다시 조회한다")
    void marksOriginalExecutionAfterReadingUncertainState() {
        var snapshot = PaymentRecoveryTask.create(key).snapshot();
        given(retry.apply(key)).willThrow(new PaymentApplicationFailureException(new IllegalStateException("commit 미확인"), false));
        given(application.currentResult(key, false)).willReturn(new ApplyOrderPaymentResult(ApplyOrderPaymentResult.State.PENDING, false));
        var incomplete = new ApplyOrderPaymentResult(ApplyOrderPaymentResult.State.APPLICATION_FAILED, true);
        given(application.currentResult(key, true)).willReturn(incomplete);

        assertThat(facade.execute(key, snapshot)).isEqualTo(incomplete);
        var ordered = inOrder(application, progress);
        ordered.verify(application).currentResult(key, false);
        ordered.verify(progress).markApplicationFailed(snapshot);
        ordered.verify(application).currentResult(key, true);
    }

    @Test
    @DisplayName("commit 미확인 뒤 상태 조회도 실패하면 표식이나 추가 반영으로 추측하지 않는다")
    void doesNotMarkWhenUncertainStateCannotBeRead() {
        var snapshot = PaymentRecoveryTask.create(key).snapshot();
        given(retry.apply(key)).willThrow(new PaymentApplicationFailureException(new IllegalStateException("commit 미확인"), false));
        var readFailure = new IllegalStateException("상태 조회 장애");
        given(application.currentResult(key, false)).willThrow(readFailure);

        assertThatThrownBy(() -> facade.execute(key, snapshot)).isSameAs(readFailure);
        verify(progress, never()).markApplicationFailed(snapshot);
    }
}
