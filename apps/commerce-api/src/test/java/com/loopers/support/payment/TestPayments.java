package com.loopers.support.payment;

import com.loopers.application.payment.PaymentConfirmCommand;
import com.loopers.application.payment.PaymentFacade;
import com.loopers.application.payment.PaymentTransactions;
import com.loopers.domain.payment.CardNumber;
import com.loopers.domain.payment.CardType;
import com.loopers.domain.payment.LookupResult;
import com.loopers.domain.payment.PaymentDiscrepancy;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentAttemptKey;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PgTransaction;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class TestPayments {

    private static final CardNumber CARD = CardNumber.of("0000-0000-0000-0000");

    private final PaymentFacade paymentFacade;
    private final PaymentTransactions paymentTransactions;
    private final PaymentRepository paymentRepository;

    TestPayments(PaymentFacade paymentFacade, PaymentTransactions paymentTransactions,
                 PaymentRepository paymentRepository) {
        this.paymentFacade = paymentFacade;
        this.paymentTransactions = paymentTransactions;
        this.paymentRepository = paymentRepository;
    }

    public Payment start(Long userId, Long orderId, Instant now) {
        return start(userId, orderId, "attempt-" + orderId, now);
    }

    public Payment start(Long userId, Long orderId, String attemptKey, Instant now) {
        return paymentFacade.confirm(new PaymentConfirmCommand(
            userId, orderId, PaymentAttemptKey.of(attemptKey), CardType.TEST, CARD), now);
    }

    public Payment startAndSucceed(Long userId, Long orderId, Instant now) {
        start(userId, orderId, now);
        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        return paymentTransactions.observe(payment.getId(), new LookupResult.Found(List.of(new PgTransaction(
                payment.getTransactionKey(), payment.getProviderOrderId(), payment.getAmount(), PgTransaction.Status.SUCCESS))),
            PaymentDiscrepancy.Source.LOOKUP, now);
    }
}
