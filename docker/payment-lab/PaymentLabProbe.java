package com.loopers.lab;

import com.loopers.CommerceApiApplication;
import com.loopers.application.pay.gateway.PaymentProviderGateway;
import com.loopers.application.pay.usecase.RecordPaymentObservationUseCase;
import com.loopers.domain.mall.model.StockAdjustment;
import com.loopers.domain.mall.repository.StockAdjustmentRepository;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentObservation;
import com.loopers.domain.pay.model.PaymentRecoveryTask;
import com.loopers.domain.pay.model.PaymentScenario;
import com.loopers.domain.pay.model.ProviderPaymentStatus;
import com.loopers.domain.pay.repository.PaymentAttemptRepository;
import com.loopers.domain.pay.repository.PaymentRecoveryTaskRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 수동 보존 실험 전용. 새로 만든 빈 DB에서 exercise 후 별도 JVM으로 verify한다. 제품 API가 아니다. */
public final class PaymentLabProbe {
    private static final List<PaymentScenario.Preset> CASES = List.of(PaymentScenario.Preset.NORMAL_SUCCESS,
            PaymentScenario.Preset.LOST_SUCCESS, PaymentScenario.Preset.LOST_FAILURE,
            PaymentScenario.Preset.DELAYED_SUCCESS, PaymentScenario.Preset.REJECT_BEFORE_CREATE);

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !(args[0].equals("exercise") || args[0].equals("verify"))) {
            throw new IllegalArgumentException("exercise 또는 verify를 지정하세요.");
        }
        try (var context = new SpringApplicationBuilder(CommerceApiApplication.class).web(WebApplicationType.NONE)
                .run("--spring.profiles.active=payment-lab", "--spring.main.web-application-type=none",
                        "--scheduler.like-count.enabled=false")) {
            if (args[0].equals("exercise")) {
                exercise(context);
            } else {
                verify(context);
            }
            System.out.println("PAYMENT_LAB_" + args[0].toUpperCase() + "_OK");
        }
        // 독립 실험 JVM은 라이브러리의 공유 스레드가 남아 있어도 여기서 종료한다.
        System.exit(0);
    }

    private static void exercise(ConfigurableApplicationContext context) throws Exception {
        var attempts = context.getBean(PaymentAttemptRepository.class);
        var tasks = context.getBean(PaymentRecoveryTaskRepository.class);
        var stocks = context.getBean(StockAdjustmentRepository.class);
        var gateway = context.getBean(PaymentProviderGateway.class);
        var observations = context.getBean(RecordPaymentObservationUseCase.class);
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        for (var preset : CASES) {
            PaymentScenario base = PaymentScenario.resolve(preset, null);
            PaymentScenario scenario = new PaymentScenario(base.rejectBeforeCreate(), base.finalStatus(),
                    base.processingDelayMs(), base.createResponseMode(), base.createResponseDelayMs(),
                    base.lookupResponseMode(), base.lookupResponseDelayMs(), PaymentScenario.CallbackMode.DROP, 0);
            long orderId = orderId(preset);
            String key = key(preset);
            PaymentAttempt attempt = PaymentAttempt.create(orderId, key, 7000, scenario).startDispatch();
            tx.executeWithoutResult(status -> {
                attempts.insert(attempt);
                tasks.insert(PaymentRecoveryTask.create(key));
                stocks.insert(StockAdjustment.create(orderId, List.of(new StockAdjustment.Item(1, 2))));
                if (preset == PaymentScenario.Preset.NORMAL_SUCCESS) {
                    String execution = key(PaymentScenario.Preset.PENDING);
                    check(tasks.claim(key, execution, Instant.now()), "실행 권한");
                    check(tasks.consumeRound(key, execution, 0, Instant.now()), "첫 회차");
                    check(tasks.consumeRound(key, execution, 1, Instant.now()), "둘째 회차");
                }
            });
            var expected = new PaymentProviderGateway.ExpectedPayment(attempt.getProviderOrderId(), 7000, null);
            PaymentObservation created = gateway.create(expected,
                    new PaymentProviderGateway.CardInput("TEST", "0000-0000-0000-0000"), scenario);
            if (preset == PaymentScenario.Preset.REJECT_BEFORE_CREATE) {
                check(created.kind() == PaymentObservation.Kind.REJECTED_NO_EFFECT, "생성 전 거절");
                observations.execute(key, created);
                check(gateway.lookup(expected).kind() == PaymentObservation.Kind.NOT_FOUND, "거절 거래 0건");
            } else {
                if (preset == PaymentScenario.Preset.NORMAL_SUCCESS) {
                    check(created.kind() == PaymentObservation.Kind.VERIFIED
                            && created.status() == ProviderPaymentStatus.PENDING, "생성 응답 snapshot");
                } else {
                    check(created.kind() == PaymentObservation.Kind.UNAVAILABLE
                            && created.reason() == PaymentObservation.Reason.TIMEOUT, "유실·지연 timeout");
                }
                observations.execute(key, created);
                PaymentObservation found = null;
                // 실험의 완료 확인용 대기이며 제품의 자동 재조회 정책 구현이 아니다.
                for (int i = 0; i < 20; i++) {
                    found = gateway.lookup(expected);
                    if (found.kind() == PaymentObservation.Kind.VERIFIED && found.status() == scenario.finalStatus()) {
                        break;
                    }
                    Thread.sleep(250);
                }
                check(found != null && found.kind() == PaymentObservation.Kind.VERIFIED
                        && found.status() == scenario.finalStatus(), "실제 PG 최종 결과");
                observations.execute(key, found);
            }
        }
    }

    private static void verify(ConfigurableApplicationContext context) {
        var attempts = context.getBean(PaymentAttemptRepository.class);
        var tasks = context.getBean(PaymentRecoveryTaskRepository.class);
        var stocks = context.getBean(StockAdjustmentRepository.class);
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        tx.executeWithoutResult(status -> {
            for (var preset : CASES) {
                PaymentAttempt attempt = attempts.findByKey(key(preset)).orElseThrow();
                boolean failed = preset == PaymentScenario.Preset.LOST_FAILURE
                        || preset == PaymentScenario.Preset.REJECT_BEFORE_CREATE;
                check(attempt.getOutcome() == (failed ? PaymentAttempt.Outcome.FAILED : PaymentAttempt.Outcome.SUCCESS),
                        "재기동 후 관찰 결과");
                check(attempt.getScenario().callbackMode() == PaymentScenario.CallbackMode.DROP, "실효 조건 보존");
                if (preset == PaymentScenario.Preset.REJECT_BEFORE_CREATE) {
                    check(attempt.getRejection() == PaymentAttempt.Rejection.NO_EFFECT
                            && attempt.getTransactionKey() == null, "거절 근거 보존");
                } else {
                    check(attempt.getTransactionKey() != null, "거래 키 보존");
                }
                PaymentRecoveryTask task = tasks.findByKey(key(preset)).orElseThrow();
                int used = preset == PaymentScenario.Preset.NORMAL_SUCCESS ? 2 : 0;
                check(task.getUsedRounds() == used, "조회 사용 횟수 보존");
                check(task.getState() == (used == 2 ? PaymentRecoveryTask.State.RUNNING : PaymentRecoveryTask.State.READY),
                        "복구 진행 보존");
                StockAdjustment stock = stocks.findByOrderId(orderId(preset)).orElseThrow();
                check(stock.getState() == StockAdjustment.State.DEDUCTED && stock.getItems().getFirst().quantity() == 2,
                        "업무 반영 전 차감 기록 보존");
            }
        });
        PaymentAttempt known = tx.execute(status -> attempts.findByKey(key(PaymentScenario.Preset.NORMAL_SUCCESS)).orElseThrow());
        var expected = new PaymentProviderGateway.ExpectedPayment(known.getProviderOrderId(), known.getAmount(),
                known.getTransactionKey());
        // HTTP는 위 DB 읽기 TX가 끝난 뒤 호출한다.
        PaymentObservation found = context.getBean(PaymentProviderGateway.class).lookup(expected);
        check(found.kind() == PaymentObservation.Kind.VERIFIED && found.status() == ProviderPaymentStatus.SUCCESS
                && known.getTransactionKey().equals(found.transactionKey()), "거래 키 조회 실연결·재시작 결과");
    }

    private static long orderId(PaymentScenario.Preset preset) {
        return 910000000L + preset.ordinal();
    }

    private static String key(PaymentScenario.Preset preset) {
        return UUID.nameUUIDFromBytes(("payment-lab-" + preset).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void check(boolean condition, String description) {
        if (!condition) {
            throw new IllegalStateException(description + " 검증 실패");
        }
    }
}
