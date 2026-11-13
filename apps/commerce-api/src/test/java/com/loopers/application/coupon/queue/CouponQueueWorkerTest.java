package com.loopers.application.coupon.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.loopers.application.coupon.CouponIssuanceTransactions;
import com.loopers.domain.coupon.Coupon;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZonedDateTime;
import java.sql.SQLException;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class CouponQueueWorkerTest {
    @Mock private CouponRequestQueue queue;
    @Mock private CouponIssuanceTransactions issuance;

    private CouponQueueWorker worker;
    private CouponQueueWorkerState state;

    @BeforeEach
    void setUp() {
        state = new CouponQueueWorkerState(true);
        worker = new CouponQueueWorker(queue, issuance, state, 250);
        lenient().when(queue.reserveAttempt(any())).thenReturn(new CouponRequestQueue.AttemptReservation(false, 0));
    }

    @Test
    void 빈_대기열이면_DB_발급을_시작하지_않는다() {
        when(queue.claim(any())).thenReturn(Optional.empty());

        worker.runOnce();

        verifyNoInteractions(issuance);
    }

    @Test
    void 발급_처리가_성공하면_신청_기록을_정리한다() {
        var claim = new CouponRequestQueue.Claim("ticket-1", 7, 11, "worker-token");
        var coupon = new Coupon(42, 7, 11, "할인권", ZonedDateTime.now());
        when(queue.claim(any())).thenReturn(Optional.of(claim));
        when(issuance.issue(eq(7L), eq(11L), any()))
                .thenReturn(new CouponIssuanceTransactions.IssueResult(coupon, true));

        worker.runOnce();

        verify(queue).complete(claim);
    }

    @Test
    void 기존_쿠폰이_확인되면_신청_기록을_정리한다() {
        var claim = new CouponRequestQueue.Claim("ticket-existing", 7, 11, "worker-token");
        var existing = new Coupon(42, 7, 11, "할인권", ZonedDateTime.now());
        when(queue.claim(any())).thenReturn(Optional.of(claim));
        when(issuance.issue(eq(7L), eq(11L), any()))
                .thenReturn(new CouponIssuanceTransactions.IssueResult(existing, false));

        worker.runOnce();

        verify(queue).complete(claim);
    }

    @Test
    void 마지막_쿠폰_경쟁에서_기존_쿠폰을_확인하면_품절_표시_후_신청을_정리한다() {
        var claim = new CouponRequestQueue.Claim("ticket-race", 7, 11, "worker-token");
        var existing = new Coupon(42, 7, 11, "할인권", ZonedDateTime.now());
        when(queue.claim(any())).thenReturn(Optional.of(claim));
        when(issuance.issue(eq(7L), eq(11L), any())).thenAnswer(invocation -> {
            CouponIssuanceTransactions.Completion completion = invocation.getArgument(2);
            reportRollback(completion);
            ReflectionTestUtils.setField(completion, "soldOutDetected", true);
            return new CouponIssuanceTransactions.IssueResult(existing, false);
        });

        worker.runOnce();

        var sequence = inOrder(queue);
        sequence.verify(queue).markSoldOut(7);
        sequence.verify(queue).complete(claim);
    }

    @Test
    void DB_종료가_불명확하면_처리중_신청을_보존하고_새_신청을_꺼내지_않는다() {
        var claim = new CouponRequestQueue.Claim("ticket-1", 7, 11, "worker-token");
        when(queue.claim(any())).thenReturn(Optional.of(claim));
        when(issuance.issue(eq(7L), eq(11L), any())).thenThrow(new IllegalStateException("DB outcome unknown"));

        worker.runOnce();
        worker.runOnce();

        assertThat(state.acceptingNewRequests()).isFalse();
        verify(queue).claim(any());
        verify(queue, never()).complete(any());
        verify(queue, never()).retry(any());
    }

    @Test
    void Redis에서_꺼낸_결과가_불명확하면_복구되더라도_해당_서버의_신규_접수를_닫는다() {
        when(queue.claim(any())).thenThrow(new IllegalStateException("claim reply lost"));

        worker.runOnce();
        worker.runOnce();

        assertThat(state.acceptingNewRequests()).isFalse();
        verify(queue).claim(any());
        verifyNoInteractions(issuance);
    }

    @Test
    void 사전_읽기_실패는_신청을_보존하고_수동_복구를_기다린다() {
        var claim = new CouponRequestQueue.Claim("ticket-read-retry", 7, 11, "worker-token");
        when(queue.claim(any())).thenReturn(Optional.of(claim));
        when(issuance.issue(eq(7L), eq(11L), any())).thenAnswer(invocation -> {
            CouponIssuanceTransactions.Completion completion = invocation.getArgument(2);
            ReflectionTestUtils.setField(completion, "writeStarted", false);
            reportRollback(completion);
            throw new IllegalStateException("read failed before write");
        });

        worker.runOnce();
        verify(queue, never()).complete(any());
        worker.runOnce();

        verify(queue).claim(any());
        verify(issuance).issue(eq(7L), eq(11L), any());
        assertThat(state.acceptingNewRequests()).isFalse();
        verify(queue, never()).scheduleRetry(any());
    }

    @Test
    void 롤백된_MySQL_데드락은_세번_재시도한_뒤_신청을_정리한다() {
        // 활성 수용기, 재시도 0회, 처리 중 신청 한 건에서 시작한다.
        var claim = new CouponRequestQueue.Claim("ticket-deadlock", 7, 11, "worker-token");
        when(queue.claim(any())).thenReturn(Optional.of(claim));
        when(issuance.issue(eq(7L), eq(11L), any())).thenAnswer(invocation -> {
            reportRollback(invocation.getArgument(2));
            throw new RuntimeException(new SQLException("deadlock", "40001", 1213));
        });
        when(queue.scheduleRetry(claim)).thenReturn(
                new CouponRequestQueue.RetrySchedule(false, 0),
                new CouponRequestQueue.RetrySchedule(false, 0),
                new CouponRequestQueue.RetrySchedule(false, 0),
                new CouponRequestQueue.RetrySchedule(true, 0));

        worker.runOnce();
        worker.runOnce();
        worker.runOnce();
        worker.runOnce();

        verify(issuance, org.mockito.Mockito.times(4)).issue(eq(7L), eq(11L), any());
        verify(queue, org.mockito.Mockito.times(4)).scheduleRetry(claim);
        verify(queue).complete(claim);
        assertThat(state.acceptingNewRequests()).isTrue();
    }

    @Test
    void 쓰기_롤백이_확인되지_않은_잠금_오류는_재시도하지_않는다() {
        var claim = new CouponRequestQueue.Claim("ticket-unknown", 7, 11, "worker-token");
        when(queue.claim(any())).thenReturn(Optional.of(claim));
        when(issuance.issue(eq(7L), eq(11L), any()))
                .thenThrow(new RuntimeException(new SQLException("deadlock", "40001", 1213)));

        worker.runOnce();

        assertThat(state.acceptingNewRequests()).isFalse();
        verify(queue, never()).scheduleRetry(any());
        verify(queue, never()).complete(any());
    }

    @Test
    void DB_권한_오류는_롤백되어도_자동_재시도하지_않는다() {
        var claim = new CouponRequestQueue.Claim("ticket-permission", 7, 11, "worker-token");
        when(queue.claim(any())).thenReturn(Optional.of(claim));
        when(issuance.issue(eq(7L), eq(11L), any())).thenAnswer(invocation -> {
            reportRollback(invocation.getArgument(2));
            throw new RuntimeException(new SQLException("access denied", "42000", 1142));
        });

        worker.runOnce();

        assertThat(state.acceptingNewRequests()).isFalse();
        verify(queue, never()).scheduleRetry(any());
    }

    @Test
    void 복구된_신청의_재시도_시각이_남아있으면_DB_발급을_시작하지_않는다() {
        var claim = new CouponRequestQueue.Claim("ticket-delayed", 7, 11, "worker-token", 60_000, false);
        when(queue.claim(any())).thenReturn(Optional.of(claim));

        worker.runOnce();
        worker.runOnce();

        verifyNoInteractions(issuance);
        assertThat(state.acceptingNewRequests()).isTrue();
    }

    @Test
    void 복구된_신청이_이미_재시도_한도를_넘겼다면_DB_발급없이_정리한다() {
        var claim = new CouponRequestQueue.Claim("ticket-exhausted", 7, 11, "worker-token");
        when(queue.claim(any())).thenReturn(Optional.of(claim));
        when(queue.reserveAttempt(claim)).thenReturn(new CouponRequestQueue.AttemptReservation(true, 0));

        worker.runOnce();

        verifyNoInteractions(issuance);
        verify(queue).complete(claim);
    }

    @Test
    void 복구한_재시도_소진_신청은_DB를_건드리지_않고_정리한다() {
        var claim = new CouponRequestQueue.Claim("ticket-committed", 7, 11, "worker-token", 0, true);
        when(queue.claim(any())).thenReturn(Optional.of(claim));
        when(queue.reserveAttempt(claim)).thenReturn(new CouponRequestQueue.AttemptReservation(true, 0));

        worker.runOnce();

        verifyNoInteractions(issuance);
        verify(queue).complete(claim);
    }

    @Test
    void DB_시도_예약_결과가_불명확하면_발급하지_않고_수동_대기한다() {
        var claim = new CouponRequestQueue.Claim("ticket-reserve", 7, 11, "worker-token");
        when(queue.claim(any())).thenReturn(Optional.of(claim));
        when(queue.reserveAttempt(claim)).thenThrow(new IllegalStateException("reservation response lost"));

        worker.runOnce();

        assertThat(state.acceptingNewRequests()).isFalse();
        verifyNoInteractions(issuance);
        verify(queue, never()).complete(any());
    }

    @Test
    void DB_시도_예약_중_다른_요청에서_장애를_감지하면_발급을_시작하지_않는다() {
        var claim = new CouponRequestQueue.Claim("ticket-closed", 7, 11, "worker-token");
        when(queue.claim(any())).thenReturn(Optional.of(claim));
        when(queue.reserveAttempt(claim)).thenAnswer(invocation -> {
            state.pause();
            return new CouponRequestQueue.AttemptReservation(false, 0, 1);
        });

        worker.runOnce();

        assertThat(state.acceptingNewRequests()).isFalse();
        verifyNoInteractions(issuance);
        verify(queue, never()).complete(any());
    }

    @Test
    void 신청_정리_응답이_불명확하면_재시도하지_않고_수동_복구를_기다린다() {
        var claim = new CouponRequestQueue.Claim("ticket-1", 7, 11, "worker-token");
        var coupon = new Coupon(42, 7, 11, "할인권", ZonedDateTime.now());
        when(queue.claim(any())).thenReturn(Optional.of(claim));
        when(issuance.issue(eq(7L), eq(11L), any()))
                .thenReturn(new CouponIssuanceTransactions.IssueResult(coupon, true));
        doThrow(new IllegalStateException("Redis outcome unknown"))
                .when(queue).complete(claim);

        worker.runOnce();
        assertThat(state.acceptingNewRequests()).isFalse();
        worker.runOnce();

        assertThat(state.acceptingNewRequests()).isFalse();
        verify(issuance).issue(eq(7L), eq(11L), any());
        verify(queue).complete(claim);
    }

    @Test
    void 완료할_신청이나_소유자가_다르면_재시도하지_않고_해당_서버의_접수를_닫는다() {
        var claim = new CouponRequestQueue.Claim("ticket-1", 7, 11, "worker-token");
        var coupon = new Coupon(42, 7, 11, "할인권", ZonedDateTime.now());
        when(queue.claim(any())).thenReturn(Optional.of(claim));
        when(issuance.issue(eq(7L), eq(11L), any()))
                .thenReturn(new CouponIssuanceTransactions.IssueResult(coupon, true));
        doThrow(new CouponQueueInconsistentStateException()).when(queue).complete(claim);

        worker.runOnce();
        worker.runOnce();

        assertThat(state.acceptingNewRequests()).isFalse();
        verify(issuance).issue(eq(7L), eq(11L), any());
        verify(queue).complete(claim);
    }

    @Test
    void 대기중인_작업이_있으면_빈_대기열용_폴링_간격을_기다리지_않고_연속으로_처리한다() {
        var first = new CouponRequestQueue.Claim("ticket-1", 7, 11, "worker-token-1");
        var second = new CouponRequestQueue.Claim("ticket-2", 7, 12, "worker-token-2");
        when(queue.claim(any())).thenReturn(Optional.of(first), Optional.of(second), Optional.empty());
        when(issuance.issue(eq(7L), eq(11L), any()))
                .thenReturn(new CouponIssuanceTransactions.IssueResult(
                        new Coupon(42, 7, 11, "할인권", ZonedDateTime.now()), true));
        when(issuance.issue(eq(7L), eq(12L), any()))
                .thenReturn(new CouponIssuanceTransactions.IssueResult(
                        new Coupon(43, 7, 12, "할인권", ZonedDateTime.now()), true));
        worker = new CouponQueueWorker(queue, issuance, state, 5_000);

        worker.start();
        try {
            verify(queue, timeout(3_000)).complete(second);
            verify(queue).complete(first);
        } finally {
            worker.stop();
        }
    }

    @Test
    void DB에서_품절이_확인되면_해당_캠페인의_품절을_표시한_뒤_신청을_정리한다() {
        var claim = new CouponRequestQueue.Claim("ticket-1", 7, 11, "worker-token");
        when(queue.claim(any())).thenReturn(Optional.of(claim));
        when(issuance.issue(eq(7L), eq(11L), any())).thenAnswer(invocation -> {
            // 트랜잭션 완료 후 전달되는 롤백 상태를 재현한다.
            reportRollback(invocation.getArgument(2));
            throw new CoreException(ErrorType.COUPON_SOLD_OUT);
        });

        worker.runOnce();

        var sequence = inOrder(queue);
        sequence.verify(queue).claim(any());
        sequence.verify(queue).markSoldOut(7);
        sequence.verify(queue).complete(claim);
    }

    @Test
    void 비활성_캠페인의_신청은_발급하지_않고_정리한다() {
        var claim = new CouponRequestQueue.Claim("ticket-inactive", 7, 11, "worker-token");
        when(queue.claim(any())).thenReturn(Optional.of(claim));
        when(issuance.issue(eq(7L), eq(11L), any())).thenAnswer(invocation -> {
            reportRollback(invocation.getArgument(2));
            throw new CoreException(ErrorType.COUPON_CAMPAIGN_INACTIVE);
        });

        worker.runOnce();

        verify(queue).complete(claim);
        assertThat(state.acceptingNewRequests()).isTrue();
    }

    @Test
    void 품절_상태_기록이_실패하면_신청을_완료하지_않고_수동_복구를_기다린다() {
        var claim = new CouponRequestQueue.Claim("ticket-1", 7, 11, "worker-token");
        when(queue.claim(any())).thenReturn(Optional.of(claim));
        when(issuance.issue(eq(7L), eq(11L), any())).thenAnswer(invocation -> {
            reportRollback(invocation.getArgument(2));
            throw new CoreException(ErrorType.COUPON_SOLD_OUT);
        });
        doThrow(new IllegalStateException("Redis outcome unknown")).when(queue).markSoldOut(7);

        worker.runOnce();
        verify(queue, never()).complete(any());
        worker.runOnce();

        assertThat(state.acceptingNewRequests()).isFalse();
        verify(issuance).issue(eq(7L), eq(11L), any());
        verify(queue).markSoldOut(7);
        verify(queue, never()).complete(any());
    }

    @Test
    void 기존_ID_회복_후_품절_상태_기록에_실패하면_수동_복구를_기다린다() {
        var claim = new CouponRequestQueue.Claim("ticket-soldout-recovery", 7, 11, "worker-token");
        var existing = new Coupon(42, 7, 11, "할인권", ZonedDateTime.now());
        when(queue.claim(any())).thenReturn(Optional.of(claim));
        when(issuance.issue(eq(7L), eq(11L), any())).thenAnswer(invocation -> {
            CouponIssuanceTransactions.Completion completion = invocation.getArgument(2);
            reportRollback(completion);
            ReflectionTestUtils.setField(completion, "soldOutDetected", true);
            return new CouponIssuanceTransactions.IssueResult(existing, false);
        });
        doThrow(new IllegalStateException("Redis outcome unknown")).when(queue).markSoldOut(7);

        worker.runOnce();
        verify(queue, never()).complete(any());
        worker.runOnce();

        assertThat(state.acceptingNewRequests()).isFalse();
        verify(issuance).issue(eq(7L), eq(11L), any());
        verify(queue).markSoldOut(7);
        verify(queue, never()).complete(any());
    }

    private static void reportRollback(CouponIssuanceTransactions.Completion completion) {
        ReflectionTestUtils.setField(completion, "outcome", CouponIssuanceTransactions.Outcome.ROLLED_BACK);
    }
}
