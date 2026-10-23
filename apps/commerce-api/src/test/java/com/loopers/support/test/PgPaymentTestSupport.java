package com.loopers.support.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.loopers.application.ordering.command.ConfirmOrderCommand;
import com.loopers.application.ordering.result.ConfirmOrderResult;
import com.loopers.application.ordering.usecase.ApplyOrderPaymentUseCase;
import com.loopers.application.ordering.usecase.ConfirmOrderUseCase;
import com.loopers.application.pay.gateway.PaymentProviderGateway;
import com.loopers.application.pay.gateway.PaymentProviderGateway.CardInput;
import com.loopers.application.pay.gateway.PaymentProviderGateway.ExpectedPayment;
import com.loopers.application.pay.usecase.RecordPaymentObservationUseCase;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentObservation;
import com.loopers.domain.pay.model.PaymentScenario;
import com.loopers.domain.pay.model.ProviderPaymentStatus;
import com.loopers.domain.pay.repository.PaymentAttemptRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 생성은 실제 계약대로 PENDING이다. 완료 관찰은 별도 경계에서 명시적으로 전달한다. */
@Component
public class PgPaymentTestSupport {
    private final PaymentProviderGateway gateway;
    private final ConfirmOrderUseCase confirm;
    private final PaymentAttemptRepository attempts;
    private final RecordPaymentObservationUseCase observations;
    private final ApplyOrderPaymentUseCase application;
    private final TransactionTemplate tx;

    public PgPaymentTestSupport(PaymentProviderGateway gateway, ConfirmOrderUseCase confirm, PaymentAttemptRepository attempts,
                                RecordPaymentObservationUseCase observations, ApplyOrderPaymentUseCase application,
                                PlatformTransactionManager transactionManager) {
        this.gateway = gateway;
        this.confirm = confirm;
        this.attempts = attempts;
        this.observations = observations;
        this.application = application;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public void usePendingProvider() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            ExpectedPayment expected = invocation.getArgument(0);
            return PaymentObservation.verified("test-" + expected.providerOrderId(), expected.providerOrderId(),
                expected.amount(), ProviderPaymentStatus.PENDING);
        }).when(gateway).create(any(), any(), any());
    }

    public ConfirmOrderResult confirmAndComplete(Order order) {
        confirm.execute(command(order));
        complete(order.getId());
        return confirm.execute(command(order));
    }

    public void complete(long orderId) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() ->
            tx.execute(status -> attempts.findByOrderId(orderId).map(attempt -> attempt.getTransactionKey() != null).orElse(false)));
        PaymentAttempt attempt = tx.execute(status -> attempts.findByOrderId(orderId).orElseThrow());
        observations.execute(attempt.getAttemptKey(), PaymentObservation.verified(attempt.getTransactionKey(),
            attempt.getProviderOrderId(), attempt.getAmount(), ProviderPaymentStatus.SUCCESS));
        application.execute(attempt.getAttemptKey());
    }

    public static ConfirmOrderCommand command(Order order) { return command(order.getUserId(), order.getId()); }

    public static ConfirmOrderCommand command(long userId, long orderId) {
        String key = UUID.nameUUIDFromBytes(("order:" + orderId).getBytes(StandardCharsets.UTF_8)).toString();
        return new ConfirmOrderCommand(userId, orderId, key, new CardInput("TEST", "0000-0000-0000-0000"),
            PaymentScenario.resolve(null, null));
    }
}
