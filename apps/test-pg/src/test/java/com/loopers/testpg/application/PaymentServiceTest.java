package com.loopers.testpg.application;

import com.loopers.testpg.domain.Payment;
import com.loopers.testpg.domain.PaymentRepository;
import com.loopers.testpg.domain.PaymentScenario;
import com.loopers.testpg.domain.PaymentStats;
import com.loopers.testpg.domain.PaymentStatsRepository;
import com.loopers.testpg.domain.PaymentStatus;
import com.loopers.testpg.domain.ScenarioRepository;
import com.loopers.testpg.domain.ScenarioSetting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceTest {
    private static final String ORDER_ID = "LP-ORD-0000000042-7f3a9c1e";
    private static final URI CALLBACK = URI.create("http://localhost/callback");
    private static final Duration DELAY = Duration.ofSeconds(3);
    private static final Duration DROP_DELAY = Duration.ofSeconds(5);

    private final PaymentRepository repository = mock(PaymentRepository.class);
    private final ScenarioRepository scenarios = mock(ScenarioRepository.class);
    private final PaymentStatsRepository stats = mock(PaymentStatsRepository.class);
    private final PaymentScheduler scheduler = mock(PaymentScheduler.class);
    private final PaymentCallback callback = mock(PaymentCallback.class);
    private final PaymentService service = service(ProcessingMode.AUTO);

    @BeforeEach
    void defaultScenario() {
        when(scenarios.find(any())).thenReturn(ScenarioSetting.defaultSetting());
    }

    @Nested
    class AutomaticProcessing {

        @DisplayName("결과 저장 뒤 콜백을 별도 실행기에 제출하고, 실제 전송이 시작될 때 호출 수를 기록한다.")
        @Test
        void dispatchesCallbackAfterCompleting() {
            List<Runnable> callbacks = new ArrayList<>();
            PaymentService target = service(ProcessingMode.AUTO, callbacks::add);
            target.create(ORDER_ID, 7_000L, CALLBACK);
            ArgumentCaptor<Payment> stored = ArgumentCaptor.forClass(Payment.class);
            Runnable task = scheduledCompletion(stored);
            Payment succeeded = stored.getValue().succeed();
            when(repository.completeIfPending(succeeded.getTransactionKey(), PaymentStatus.SUCCESS))
                .thenReturn(Optional.of(succeeded)).thenReturn(Optional.empty());

            task.run();
            task.run();

            assertThat(callbacks).hasSize(1);
            verify(callback, never()).send(any());
            verify(stats, never()).recordCallbackAttempt(ORDER_ID);
            callbacks.getFirst().run();
            verify(callback).send(succeeded);
            verify(stats).recordCallbackAttempt(ORDER_ID);
        }

        @DisplayName("콜백 실행기가 작업을 거절해도 결제 완료는 유지하고 전송 횟수를 늘리거나 자동 재전송하지 않는다.")
        @Test
        void keepsCompletionWhenCallbackQueueIsFull() {
            PaymentService target = service(ProcessingMode.AUTO, task -> {
                throw new RejectedExecutionException("queue full");
            });
            target.create(ORDER_ID, 7_000L, CALLBACK);
            ArgumentCaptor<Payment> stored = ArgumentCaptor.forClass(Payment.class);
            Runnable task = scheduledCompletion(stored);
            Payment succeeded = stored.getValue().succeed();
            when(repository.completeIfPending(succeeded.getTransactionKey(), PaymentStatus.SUCCESS))
                .thenReturn(Optional.of(succeeded)).thenReturn(Optional.empty());

            assertThatCode(task::run).doesNotThrowAnyException();
            task.run();

            verify(callback, never()).send(any());
            verify(stats, never()).recordCallbackAttempt(ORDER_ID);
        }

        @DisplayName("접수는 PENDING을 반환하고 성공 작업을 3초 뒤로 예약하며, 같은 작업이 다시 실행돼도 callback은 한 번이다.")
        @Test
        void acceptsBeforeCompletingAndNotifiesOnlyOnce() {
            CreateResult.Accepted accepted = (CreateResult.Accepted) service.create(ORDER_ID, 7_000L, CALLBACK);
            ArgumentCaptor<Payment> stored = ArgumentCaptor.forClass(Payment.class);
            Runnable completion = scheduledCompletion(stored);

            assertThat(accepted.payment().status()).isEqualTo(PaymentStatus.PENDING);
            assertThat(accepted.responseDelay()).isZero();
            verify(callback, never()).send(any());

            Payment succeeded = stored.getValue().succeed();
            when(repository.completeIfPending(accepted.payment().transactionKey(), PaymentStatus.SUCCESS))
                .thenReturn(Optional.of(succeeded)).thenReturn(Optional.empty());

            completion.run();
            completion.run();

            verify(callback).send(succeeded);
            verify(stats).recordCreateRequest(ORDER_ID);
            verify(stats).recordCallbackAttempt(ORDER_ID);
        }

        @DisplayName("웹훅 전송 실패가 저장된 성공을 되돌리거나 자동 재전송하지 않는다.")
        @Test
        void keepsSuccessWhenCallbackFails() {
            CreateResult.Accepted accepted = (CreateResult.Accepted) service.create(ORDER_ID, 7_000L, CALLBACK);
            ArgumentCaptor<Payment> stored = ArgumentCaptor.forClass(Payment.class);
            Runnable completion = scheduledCompletion(stored);
            Payment succeeded = stored.getValue().succeed();
            when(repository.completeIfPending(accepted.payment().transactionKey(), PaymentStatus.SUCCESS))
                .thenReturn(Optional.of(succeeded));
            doThrow(new IllegalStateException("receiver unavailable")).when(callback).send(succeeded);

            completion.run();

            verify(callback).send(succeeded);
            verify(stats).recordCallbackAttempt(ORDER_ID);
        }
    }

    @Nested
    class Scenarios {

        @DisplayName("접수 후 주문 시나리오를 바꿔도 자동·수동 처리 모두 접수 당시의 실패 결과를 유지한다.")
        @ParameterizedTest
        @EnumSource(ProcessingMode.class)
        void keepsAcceptedResultWhenScenarioChanges(ProcessingMode mode) {
            PaymentService target = service(mode);
            scenario(ScenarioSetting.of(PaymentScenario.FAIL));
            target.create(ORDER_ID, 7_000L, CALLBACK);
            ArgumentCaptor<Payment> stored = ArgumentCaptor.forClass(Payment.class);
            verify(repository).save(stored.capture());
            Payment pending = stored.getValue();
            Payment failed = pending.fail();
            when(repository.completeIfPending(pending.getTransactionKey(), PaymentStatus.FAILED))
                .thenReturn(Optional.of(failed));
            when(repository.findAllByOrderId(ORDER_ID)).thenReturn(List.of(pending));
            scenario(ScenarioSetting.defaultSetting());

            if (mode == ProcessingMode.AUTO) {
                ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
                verify(scheduler).schedule(task.capture(), eq(DELAY));
                task.getValue().run();
            } else {
                target.completeOrder(ORDER_ID);
            }

            verify(callback).send(failed);
        }

        @DisplayName("접수 후 시나리오를 바꿔도 이미 접수된 거래의 콜백 생략 설정은 유지한다.")
        @Test
        void keepsAcceptedCallbackCountWhenScenarioChanges() {
            scenario(ScenarioSetting.of(PaymentScenario.SKIP_CALLBACK));
            service.create(ORDER_ID, 7_000L, CALLBACK);
            ArgumentCaptor<Payment> stored = ArgumentCaptor.forClass(Payment.class);
            Runnable task = scheduledCompletion(stored);
            Payment succeeded = stored.getValue().succeed();
            when(repository.completeIfPending(succeeded.getTransactionKey(), PaymentStatus.SUCCESS))
                .thenReturn(Optional.of(succeeded));
            scenario(ScenarioSetting.of(PaymentScenario.DUPLICATE_CALLBACK));

            task.run();

            verify(callback, never()).send(any());
            verify(stats, never()).recordCallbackAttempt(ORDER_ID);
        }

        @DisplayName("FAIL 시나리오는 거래를 FAILED로 종결하고 callback을 한 번 보낸다.")
        @Test
        void failsPaymentWithOneCallback() {
            scenario(ScenarioSetting.of(PaymentScenario.FAIL));
            service.create(ORDER_ID, 7_000L, CALLBACK);
            ArgumentCaptor<Payment> stored = ArgumentCaptor.forClass(Payment.class);
            Runnable completion = scheduledCompletion(stored);
            Payment failed = stored.getValue().fail();
            when(repository.completeIfPending(stored.getValue().getTransactionKey(), PaymentStatus.FAILED))
                .thenReturn(Optional.of(failed));

            completion.run();

            verify(callback).send(failed);
        }

        @DisplayName("SKIP_CALLBACK은 결과만 저장하고 callback을 보내지 않으며, DUPLICATE_CALLBACK은 같은 callback을 두 번 보낸다.")
        @Test
        void controlsCallbackCount() {
            scenario(ScenarioSetting.of(PaymentScenario.SKIP_CALLBACK));
            Payment skipped = completeOnce();
            verify(callback, never()).send(skipped);
            verify(stats, never()).recordCallbackAttempt(ORDER_ID);

            scenario(ScenarioSetting.of(PaymentScenario.DUPLICATE_CALLBACK));
            Payment duplicated = completeOnce();
            verify(callback, times(2)).send(duplicated);
            verify(stats, times(2)).recordCallbackAttempt(ORDER_ID);
        }

        @DisplayName("생성 전 확정 거절은 거래와 예약 작업을 만들지 않고 요청의 주문 ID·금액으로 거절 결과를 반환한다.")
        @Test
        void rejectsBeforeCreatingTransaction() {
            scenario(ScenarioSetting.of(PaymentScenario.REJECT_BEFORE_CREATE));

            CreateResult result = service.create(ORDER_ID, 7_000L, CALLBACK);

            assertThat(result).isEqualTo(new CreateResult.RejectedBeforeCreate(ORDER_ID, 7_000L));
            verify(repository, never()).save(any());
            verify(scheduler, never()).schedule(any(), any());
            verify(stats).recordCreateRequest(ORDER_ID);
        }

        @DisplayName("응답 대체는 지정한 상태·본문을 반환하고, 거래 생성 여부에 따라 거래와 예약 작업을 만든다.")
        @Test
        void overridesResponseWithOrWithoutTransaction() {
            scenario(ScenarioSetting.override(422, "", false));
            assertThat(service.create(ORDER_ID, 7_000L, CALLBACK)).isEqualTo(new CreateResult.Overridden(422, ""));
            verify(repository, never()).save(any());

            scenario(ScenarioSetting.override(202, "{\"broken\"", true));
            assertThat(service.create(ORDER_ID, 7_000L, CALLBACK))
                .isEqualTo(new CreateResult.Overridden(202, "{\"broken\""));
            verify(repository).save(any());
            verify(scheduler).schedule(any(), eq(DELAY));
        }

        @DisplayName("응답 유실은 거래를 저장·예약한 뒤 설정한 지연만큼 늦게 응답하도록 반환한다.")
        @Test
        void dropsResponseAfterSavingTransaction() {
            scenario(ScenarioSetting.of(PaymentScenario.DROP_RESPONSE));

            CreateResult result = service.create(ORDER_ID, 7_000L, CALLBACK);

            assertThat(result).isInstanceOfSatisfying(CreateResult.Accepted.class,
                accepted -> assertThat(accepted.responseDelay()).isEqualTo(DROP_DELAY));
            verify(repository).save(any());
            verify(scheduler).schedule(any(), eq(DELAY));
        }

        @DisplayName("올바른 주문 ID에만 시나리오를 등록한다.")
        @Test
        void registersScenarioOnlyForValidOrderId() {
            ScenarioSetting fail = ScenarioSetting.of(PaymentScenario.FAIL);

            service.registerScenario(ORDER_ID, fail);

            verify(scenarios).register(ORDER_ID, fail);
            assertThatIllegalArgumentException().isThrownBy(() -> service.registerScenario("bad id", fail));
        }
    }

    @Nested
    class ManualProcessing {

        @DisplayName("수동 처리 모드는 거래를 저장하되 예약하지 않고, 주문의 PENDING 거래만 요청할 때 처리한다.")
        @Test
        void completesPendingPaymentsOnlyOnRequest() {
            PaymentService manual = service(ProcessingMode.MANUAL);
            manual.create(ORDER_ID, 7_000L, CALLBACK);
            ArgumentCaptor<Payment> stored = ArgumentCaptor.forClass(Payment.class);
            verify(repository).save(stored.capture());
            verify(scheduler, never()).schedule(any(), any());
            Payment pending = stored.getValue();
            Payment alreadyDone = Payment.create(ORDER_ID, 7_000L, CALLBACK).succeed();
            when(repository.findAllByOrderId(ORDER_ID)).thenReturn(List.of(pending, alreadyDone));
            when(repository.completeIfPending(pending.getTransactionKey(), PaymentStatus.SUCCESS))
                .thenReturn(Optional.of(pending.succeed()));

            int completed = manual.completeOrder(ORDER_ID);

            assertThat(completed).isEqualTo(1);
            verify(repository, never()).completeIfPending(eq(alreadyDone.getTransactionKey()), any());
            verify(callback).send(any());
        }
    }

    @Nested
    class Stats {

        @DisplayName("거래를 찾은 단건 조회와 주문 단위 조회를 그 주문의 조회 요청으로 기록한다.")
        @Test
        void recordsLookupsByOrder() {
            Payment payment = Payment.create(ORDER_ID, 7_000L, CALLBACK);
            when(repository.findByTransactionKey(payment.getTransactionKey())).thenReturn(Optional.of(payment));
            when(repository.findByTransactionKey("missing")).thenReturn(Optional.empty());

            service.findByTransactionKey(payment.getTransactionKey());
            service.findByTransactionKey("missing");
            service.findAllByOrderId(ORDER_ID);

            verify(stats, times(2)).recordLookupRequest(ORDER_ID);
        }

        @DisplayName("주문의 호출 수와 저장된 거래 수를 함께 돌려주고, 이 조회 자체는 조회 요청으로 세지 않는다.")
        @Test
        void returnsCountsWithTransactionCount() {
            when(stats.find(ORDER_ID)).thenReturn(new PaymentStats(2, 3, 1));
            when(repository.findAllByOrderId(ORDER_ID)).thenReturn(List.of(
                Payment.create(ORDER_ID, 7_000L, CALLBACK), Payment.create(ORDER_ID, 7_000L, CALLBACK)
            ));

            assertThat(service.stats(ORDER_ID)).isEqualTo(new OrderStatsInfo(2, 3, 1, 2));
            verify(stats, never()).recordLookupRequest(any());
        }
    }

    private PaymentService service(ProcessingMode mode) {
        return service(mode, Runnable::run);
    }

    private PaymentService service(ProcessingMode mode, Executor executor) {
        return new PaymentService(repository, scenarios, stats, scheduler, callback, executor,
            new ProcessingOptions(mode, DELAY, DROP_DELAY));
    }

    private void scenario(ScenarioSetting setting) {
        when(scenarios.find(ORDER_ID)).thenReturn(setting);
    }

    private Runnable scheduledCompletion(ArgumentCaptor<Payment> stored) {
        ArgumentCaptor<Runnable> completion = ArgumentCaptor.forClass(Runnable.class);
        var order = inOrder(repository, scheduler);
        order.verify(repository).save(stored.capture());
        order.verify(scheduler).schedule(completion.capture(), eq(DELAY));
        return completion.getValue();
    }

    private Payment completeOnce() {
        ArgumentCaptor<Payment> stored = ArgumentCaptor.forClass(Payment.class);
        ArgumentCaptor<Runnable> completion = ArgumentCaptor.forClass(Runnable.class);
        service.create(ORDER_ID, 7_000L, CALLBACK);
        verify(repository, atLeastOnce()).save(stored.capture());
        verify(scheduler, atLeastOnce()).schedule(completion.capture(), eq(DELAY));
        Payment created = stored.getValue();
        Payment succeeded = created.succeed();
        when(repository.completeIfPending(created.getTransactionKey(), PaymentStatus.SUCCESS))
            .thenReturn(Optional.of(succeeded));
        completion.getValue().run();
        return succeeded;
    }
}
