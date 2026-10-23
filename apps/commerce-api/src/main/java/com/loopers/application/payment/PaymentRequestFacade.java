package com.loopers.application.payment;

import com.loopers.application.user.UserValidator;
import com.loopers.domain.payment.DuplicatePaymentAttemptException;
import com.loopers.domain.payment.PaymentAttempt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 결제 요청: TX A로 재고를 확보하고, commit 뒤 트랜잭션 밖에서 PG 생성을 한 번 보낸 다음 생성 응답을 반영한다.
// PG 거래는 DB rollback으로 되돌릴 수 없으므로 흐름 전체를 트랜잭션으로 감싸지 않는다.
@Slf4j
@RequiredArgsConstructor
@Component
public class PaymentRequestFacade {
    private final UserValidator userValidator;
    private final PaymentRequestTransaction paymentRequestTransaction;
    private final PaymentGateway paymentGateway;
    private final PaymentResultService paymentResultService;

    @Transactional(propagation = Propagation.NEVER)
    public PaymentStatusInfo requestPayment(Long userId, Long orderId, String paymentAttemptKey,
                                            String cardType, String cardNo) {
        String normalizedKey = PaymentAttempt.normalizeAttemptKey(paymentAttemptKey);
        PaymentCard card = new PaymentCard(cardType, cardNo);
        userValidator.validateExists(userId);

        PaymentReservation reservation;
        try {
            reservation = paymentRequestTransaction.reserve(userId, orderId, normalizedKey);
        } catch (DuplicatePaymentAttemptException exception) {
            // 시도 키·주문 유일 제약 경쟁에서 졌다. TX A는 이미 rollback됐다.
            reservation = new PaymentReservation.AlreadyRequested();
        }
        return switch (reservation) {
            case PaymentReservation.Started started -> send(started, card);
            case PaymentReservation.AlreadyRequested ignored ->
                paymentRequestTransaction.readRequested(userId, orderId, normalizedKey);
        };
    }

    // 시도를 새로 commit한 요청만 생성을 보낸다. 재시도하지 않는다.
    private PaymentStatusInfo send(PaymentReservation.Started started, PaymentCard card) {
        PaymentCreateObservation observation = paymentGateway.create(new PaymentCreateCommand(
            started.providerOrderId(), started.amount(), card.cardType(), card.cardNo()
        ));
        try {
            return paymentResultService.applyCreateResponse(started.attemptId(), observation).payment();
        } catch (RuntimeException exception) {
            // TX A는 이미 commit됐다. 저장된 상태를 다시 읽을 수 있으면 그 상태로 응답하고, 읽지 못하면 내부 오류로 끝난다.
            log.error("결제 생성 응답을 반영하지 못해 저장된 상태로 응답합니다. attemptId={}, cause={}",
                started.attemptId(), exception.toString());
            return paymentResultService.read(started.attemptId());
        }
    }
}
