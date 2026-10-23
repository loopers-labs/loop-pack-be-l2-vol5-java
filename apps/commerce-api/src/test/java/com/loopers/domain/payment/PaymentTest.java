package com.loopers.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class PaymentTest {
    private Payment successfulPayment;
    private Payment failedPayment;

    @BeforeEach
    void setUp() {
        successfulPayment = Payment.start(42, UUID.fromString("017f22e2-79b0-7cc3-98c4-dc0c0c07398f"), 7_000)
                .succeeded("TEST-TX-1");
        failedPayment = Payment.start(43, UUID.fromString("017f22e2-79b0-7cc3-98c4-dc0c0c073990"), 7_000)
                .failed("TEST-TX-2");
    }

    @Test
    void 이미_성공으로_확정된_결제에_같은_거래의_처리중_결과를_반영해도_결제_성공_상태와_거래키를_유지한다() {
        // arrange
        String sameTransactionKey = successfulPayment.transactionKey();

        // act
        var paymentAfterLateAcceptance = successfulPayment.accepted(sameTransactionKey);

        // assert
        assertThat(paymentAfterLateAcceptance.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(paymentAfterLateAcceptance.transactionKey()).isEqualTo(sameTransactionKey);
    }

    @Test
    void 이미_성공으로_확정된_결제를_결과_미확인으로_변경하려_해도_결제_성공_상태와_거래키를_유지한다() {
        // arrange
        var paymentBeforeLateFailure = successfulPayment;

        // act
        var paymentAfterLateFailure = paymentBeforeLateFailure.unknown();

        // assert
        assertThat(paymentAfterLateFailure.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(paymentAfterLateFailure.transactionKey()).isEqualTo(paymentBeforeLateFailure.transactionKey());
    }

    @Test
    void 이미_실패로_확정된_결제에_같은_거래의_처리중_결과를_반영해도_실패_상태를_유지한다() {
        // arrange
        String sameTransactionKey = failedPayment.transactionKey();

        // act
        var paymentAfterLateAcceptance = failedPayment.accepted(sameTransactionKey);

        // assert
        assertThat(paymentAfterLateAcceptance.status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void 이미_실패로_확정된_결제를_결과_미확인으로_바꾸려_해도_실패_상태를_유지한다() {
        // arrange
        var paymentBeforeLateTimeout = failedPayment;

        // act
        var paymentAfterLateTimeout = paymentBeforeLateTimeout.unknown();

        // assert
        assertThat(paymentAfterLateTimeout.status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void 이미_실패로_확정된_결제에_같은_거래의_성공을_반영하려_하면_충돌로_거절한다() {
        // arrange
        String sameTransactionKey = failedPayment.transactionKey();

        // act
        Throwable conflictingResult = catchThrowable(() -> failedPayment.succeeded(sameTransactionKey));

        // assert
        assertThat(conflictingResult).isInstanceOfSatisfying(CoreException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT));
    }

    @Test
    void 이미_성공으로_확정된_결제에_같은_거래의_실패를_반영하려_하면_충돌로_거절한다() {
        // arrange
        String sameTransactionKey = successfulPayment.transactionKey();

        // act
        Throwable conflictingResult = catchThrowable(() -> successfulPayment.failed(sameTransactionKey));

        // assert
        assertThat(conflictingResult).isInstanceOfSatisfying(CoreException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT));
    }

    @Test
    void 이미_실패로_확정된_결제에_다른_거래의_실패를_반영하려_하면_충돌로_거절한다() {
        // arrange
        String differentTransactionKey = "TEST-TX-OTHER";

        // act
        Throwable conflictingResult = catchThrowable(() -> failedPayment.failed(differentTransactionKey));

        // assert
        assertThat(conflictingResult).isInstanceOfSatisfying(CoreException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT));
    }

    @Test
    void PG가_거래_생성_전에_거절하면_거래키_없이_결제_실패로_확정한다() {
        // arrange
        var requestedPayment = Payment.start(42, UUID.fromString("017f22e2-79b0-7cc3-98c4-dc0c0c07398f"), 7_000);

        // act
        var rejectedPayment = requestedPayment.rejectedBeforeCreate();

        // assert
        assertThat(rejectedPayment.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(rejectedPayment.transactionKey()).isNull();
    }

    @Test
    void PG가_거래_생성_전에_거절한_결제에_거래키를_연결하려_하면_충돌로_거절한다() {
        // arrange
        var rejectedPayment = Payment.start(42, UUID.fromString("017f22e2-79b0-7cc3-98c4-dc0c0c07398f"), 7_000)
                .rejectedBeforeCreate();

        // act
        Throwable conflictingResult = catchThrowable(() -> rejectedPayment.accepted("UNEXPECTED-TX"));

        // assert
        assertThat(conflictingResult).isInstanceOfSatisfying(CoreException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT));
    }

    @Test
    void PG가_이미_접수한_결제를_거래_생성_전_거절로_바꾸려_하면_충돌로_거절한다() {
        // arrange
        var acceptedPayment = Payment.start(42, UUID.fromString("017f22e2-79b0-7cc3-98c4-dc0c0c07398f"), 7_000)
                .accepted("TEST-TX-1");

        // act
        Throwable conflictingResult = catchThrowable(acceptedPayment::rejectedBeforeCreate);

        // assert
        assertThat(conflictingResult).isInstanceOfSatisfying(CoreException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT));
    }

}
