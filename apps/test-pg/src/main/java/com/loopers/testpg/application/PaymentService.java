package com.loopers.testpg.application;

import com.loopers.testpg.domain.Payment;
import com.loopers.testpg.domain.PaymentRepository;
import com.loopers.testpg.domain.PaymentStats;
import com.loopers.testpg.domain.PaymentStatsRepository;
import com.loopers.testpg.domain.PaymentStatus;
import com.loopers.testpg.domain.ScenarioRepository;
import com.loopers.testpg.domain.ScenarioSetting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private final PaymentRepository repository;
    private final ScenarioRepository scenarios;
    private final PaymentStatsRepository stats;
    private final PaymentScheduler scheduler;
    private final PaymentCallback callback;
    private final Executor callbackExecutor;
    private final ProcessingOptions options;

    public PaymentService(PaymentRepository repository, ScenarioRepository scenarios, PaymentStatsRepository stats,
                          PaymentScheduler scheduler, PaymentCallback callback, Executor callbackExecutor, ProcessingOptions options) {
        this.repository = repository;
        this.scenarios = scenarios;
        this.stats = stats;
        this.scheduler = scheduler;
        this.callback = callback;
        this.callbackExecutor = callbackExecutor;
        this.options = options;
    }

    // 입력을 먼저 검증한 뒤, 주문에 등록한 시나리오로 거래 생성 여부와 응답을 정한다.
    public CreateResult create(String orderId, long amount, URI callbackUrl) {
        if (!Payment.isValidOrderId(orderId)) {
            throw new IllegalArgumentException("주문 ID가 올바르지 않습니다.");
        }
        ScenarioSetting setting = scenarios.find(orderId);
        Payment payment = Payment.create(orderId, amount, callbackUrl, setting);
        stats.recordCreateRequest(orderId);
        if (setting.createsTransaction()) {
            repository.save(payment);
            if (options.mode() == ProcessingMode.AUTO) {
                scheduler.schedule(() -> complete(payment), options.processingDelay());
            }
        }
        return switch (setting.scenario()) {
            case REJECT_BEFORE_CREATE -> new CreateResult.RejectedBeforeCreate(orderId, amount);
            case CREATE_RESPONSE_OVERRIDE -> new CreateResult.Overridden(setting.responseStatus(), setting.responseBody());
            case DROP_RESPONSE -> new CreateResult.Accepted(PaymentInfo.from(payment), options.dropResponseDelay());
            default -> new CreateResult.Accepted(PaymentInfo.from(payment), Duration.ZERO);
        };
    }

    public int completeOrder(String orderId) {
        int completed = 0;
        for (Payment payment : repository.findAllByOrderId(orderId)) {
            if (payment.getStatus() == PaymentStatus.PENDING && complete(payment)) {
                completed++;
            }
        }
        return completed;
    }

    public void registerScenario(String orderId, ScenarioSetting setting) {
        if (!Payment.isValidOrderId(orderId)) {
            throw new IllegalArgumentException("주문 ID가 올바르지 않습니다.");
        }
        scenarios.register(orderId, setting);
    }

    public OrderStatsInfo stats(String orderId) {
        PaymentStats counts = stats.find(orderId);
        return new OrderStatsInfo(counts.createRequests(), counts.lookupRequests(), counts.callbackAttempts(),
            repository.findAllByOrderId(orderId).size());
    }

    public Optional<PaymentInfo> findByTransactionKey(String transactionKey) {
        Optional<Payment> payment = repository.findByTransactionKey(transactionKey);
        payment.ifPresent(found -> stats.recordLookupRequest(found.getOrderId()));
        return payment.map(PaymentInfo::from);
    }

    public List<PaymentInfo> findAllByOrderId(String orderId) {
        stats.recordLookupRequest(orderId);
        return repository.findAllByOrderId(orderId).stream().map(PaymentInfo::from).toList();
    }

    // 최종 상태를 저장한 뒤 callback을 보낸다. 전송 실패는 저장된 결과를 되돌리지 않는다.
    private boolean complete(Payment payment) {
        Optional<Payment> done = repository.completeIfPending(payment.getTransactionKey(), payment.getCompletionResult());
        done.ifPresent(this::dispatchCallbacks);
        return done.isPresent();
    }

    private void dispatchCallbacks(Payment payment) {
        if (payment.getCallbackCount() == 0) {
            return;
        }
        try {
            callbackExecutor.execute(() -> {
                for (int attempt = 0; attempt < payment.getCallbackCount(); attempt++) {
                    send(payment);
                }
            });
        } catch (RejectedExecutionException exception) {
            log.warn("PG callback dispatch rejected. transactionKey={}, error={}",
                payment.getTransactionKey(), exception.getClass().getSimpleName());
        }
    }

    private void send(Payment payment) {
        stats.recordCallbackAttempt(payment.getOrderId());
        try {
            callback.send(payment);
        } catch (RuntimeException exception) {
            log.warn("PG callback failed. transactionKey={}, error={}",
                payment.getTransactionKey(), exception.getClass().getSimpleName());
        }
    }
}
