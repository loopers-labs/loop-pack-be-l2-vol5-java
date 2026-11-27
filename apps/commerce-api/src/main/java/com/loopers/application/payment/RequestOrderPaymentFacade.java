package com.loopers.application.payment;

import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class RequestOrderPaymentFacade {
    private final OrderPaymentTransactions transactions;
    private final PaymentGateway gateway;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Result request(Long userId, long orderId, Command command) {
        if (command == null) {
            throw new CoreException(ErrorType.INVALID_REQUEST);
        }
        var preparedPayment = transactions.prepare(userId, orderId, command.paymentAttemptKey());
        if (!preparedPayment.shouldSend()) {
            PaymentRequestDecision.REUSE_EXISTING.record(orderId, command.paymentAttemptKey());
            return Result.from(preparedPayment.payment());
        }
        PaymentRequestDecision.SEND_TO_PG.record(orderId, command.paymentAttemptKey());
        Payment paymentAfterRequest = requestPgAndRecordResult(preparedPayment.payment(), command);
        return Result.from(paymentAfterRequest);
    }

    private Payment requestPgAndRecordResult(Payment paymentBeforeRequest, Command command) {
        PaymentGateway.CreatedPayment acceptedByPg;
        try {
            acceptedByPg = gateway.create(
                    paymentBeforeRequest.providerOrderId(), paymentBeforeRequest.amount(),
                    command.cardType(), command.cardNo());
        } catch (PaymentGateway.RejectedBeforeCreateException rejection) {
            return transactions.recordRejection(paymentBeforeRequest.orderId(), rejection);
        } catch (PaymentGateway.UnconfirmedException exception) {
            logUnconfirmedPgCreate(paymentBeforeRequest, exception);
            return transactions.recordUnknown(paymentBeforeRequest.orderId());
        }
        return transactions.recordAcceptance(paymentBeforeRequest.orderId(), acceptedByPg);
    }

    private void logUnconfirmedPgCreate(Payment payment, PaymentGateway.UnconfirmedException exception) {
        String errorType = NestedExceptionUtils.getMostSpecificCause(exception).getClass().getSimpleName();
        log.atWarn().addKeyValue("step", "pg_create")
                .addKeyValue("orderId", Long.toString(payment.orderId()))
                .addKeyValue("paymentAttemptKey", payment.paymentAttemptKey().toString())
                .addKeyValue("providerOrderId", payment.providerOrderId())
                .addKeyValue("outcome", "unconfirmed")
                .addKeyValue("errorType", errorType)
                .log("step=pg_create orderId={} providerOrderId={} outcome=unconfirmed errorType={}",
                        payment.orderId(), payment.providerOrderId(), errorType);
    }

    public record Command(UUID paymentAttemptKey, String cardType, String cardNo) {
        public Command {
            if (paymentAttemptKey == null || paymentAttemptKey.version() != 7 || paymentAttemptKey.variant() != 2
                    || !"TEST".equals(cardType) || !"0000-0000-0000-0000".equals(cardNo)) {
                throw new CoreException(ErrorType.INVALID_REQUEST);
            }
        }

        @Override
        public String toString() {
            return "PaymentCommand[paymentAttemptKey=" + paymentAttemptKey + ", cardType=" + cardType + "]";
        }
    }

    public record Result(
            long orderId, OrderStatus orderStatus, PaymentStatus paymentStatus,
            String providerOrderId, String transactionKey) {
        public static Result from(Payment payment) {
            OrderStatus orderStatus = switch (payment.status()) {
                case SUCCESS -> OrderStatus.CONFIRMED;
                case FAILED -> OrderStatus.PAYMENT_FAILED;
                case PENDING, UNKNOWN -> OrderStatus.PAYMENT_PENDING;
            };
            return new Result(payment.orderId(), orderStatus, payment.status(),
                    payment.providerOrderId(), payment.transactionKey());
        }
    }
}
