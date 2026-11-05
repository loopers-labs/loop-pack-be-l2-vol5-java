package com.loopers.support.kafka;

import com.loopers.application.order.OrderConfirmedPayload;
import com.loopers.application.order.port.OrderConfirmedPublisher;
import com.loopers.application.payment.port.PaymentRecordRepository;
import com.loopers.domain.payment.PaymentRecord;
import com.loopers.infrastructure.order.KafkaOrderConfirmedPublisher;
import com.loopers.infrastructure.payment.PaymentRecordPersistenceAdapter;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.common.KafkaException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

// 주문 확정 이벤트 통합 테스트 전용 대역. 운영 코드에는 분기를 두지 않고 여기서 바꿔 끼운다.
@TestConfiguration
public class OrderEventsTestConfig {
    @Bean
    @Primary
    ControllableOrderConfirmedPublisher controllableOrderConfirmedPublisher(
        KafkaOrderConfirmedPublisher kafkaOrderConfirmedPublisher) {
        return new ControllableOrderConfirmedPublisher(kafkaOrderConfirmedPublisher);
    }

    @Bean
    @Primary
    FailingKafkaTemplate failingKafkaTemplate(
        @Qualifier("orderEventsKafkaTemplate") KafkaTemplate<String, String> orderEventsKafkaTemplate) {
        return new FailingKafkaTemplate(orderEventsKafkaTemplate);
    }

    @Bean
    PublishedOrderEvents publishedOrderEvents() {
        return new PublishedOrderEvents();
    }

    @Bean
    @Primary
    FailingPaymentRecordRepository failingPaymentRecordRepository(PaymentRecordPersistenceAdapter delegate,
        EntityManager entityManager) {
        return new FailingPaymentRecordRepository(delegate, entityManager);
    }

    // after-commit listener가 부르는 publisher. 멈춤을 걸면 commit 뒤 send 직전에서 멈춘 것처럼 payload만 남기고 보내지 않는다.
    public static class ControllableOrderConfirmedPublisher implements OrderConfirmedPublisher {
        private final OrderConfirmedPublisher delegate;
        private final AtomicBoolean haltBeforeSend = new AtomicBoolean();
        private final List<OrderConfirmedPayload> halted = new CopyOnWriteArrayList<OrderConfirmedPayload>();
        private final List<OrderConfirmedPayload> sent = new CopyOnWriteArrayList<OrderConfirmedPayload>();

        ControllableOrderConfirmedPublisher(OrderConfirmedPublisher delegate) {
            this.delegate = delegate;
        }

        public void haltBeforeSend() {
            haltBeforeSend.set(true);
        }

        public void reset() {
            haltBeforeSend.set(false);
            halted.clear();
            sent.clear();
        }

        public List<OrderConfirmedPayload> halted() {
            return List.copyOf(halted);
        }

        public List<OrderConfirmedPayload> sent() {
            return List.copyOf(sent);
        }

        @Override
        public void send(OrderConfirmedPayload payload) {
            if (haltBeforeSend.get()) {
                halted.add(payload);
                return;
            }
            sent.add(payload);
            delegate.send(payload);
        }

        @Override
        public void republish(OrderConfirmedPayload payload) {
            delegate.republish(payload);
        }
    }

    // 실제 producer factory로 보내다가, 무장하면 send를 실패시킨다. 운영 publisher의 실패 처리(로그·지표)는 그대로 거친다.
    public static class FailingKafkaTemplate extends KafkaTemplate<String, String> {
        public static final String INJECTED_FAILURE = "주입된 Kafka send 실패";

        public enum Failure { NONE, SYNC_THROW, ASYNC_FAILED }

        private final AtomicReference<Failure> failure = new AtomicReference<Failure>(Failure.NONE);

        FailingKafkaTemplate(KafkaTemplate<String, String> delegate) {
            super(delegate.getProducerFactory());
        }

        public void fail(Failure next) {
            failure.set(next);
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(String topic, String key, String data) {
            return switch (failure.get()) {
                case SYNC_THROW -> throw new KafkaException(INJECTED_FAILURE);
                case ASYNC_FAILED -> CompletableFuture.failedFuture(new KafkaException(INJECTED_FAILURE));
                case NONE -> super.send(topic, key, data);
            };
        }
    }

    // 트랜잭션 안에서 이벤트가 발행된 순간을 기록한다. after-commit listener와 달리 rollback돼도 남는다.
    public static class PublishedOrderEvents {
        private final List<OrderConfirmedPayload> published = new CopyOnWriteArrayList<OrderConfirmedPayload>();

        @EventListener
        void on(OrderConfirmedPayload payload) {
            published.add(payload);
        }

        public List<OrderConfirmedPayload> published() {
            return List.copyOf(published);
        }

        public void reset() {
            published.clear();
        }
    }

    // 무장하면 다음 결제 기록 저장에서 앞선 주문 변경을 DB로 보낸 뒤 실패한다.
    public static class FailingPaymentRecordRepository implements PaymentRecordRepository {
        public static final String INJECTED_FAILURE = "주입된 결제 기록 저장 실패";

        private final PaymentRecordRepository delegate;
        private final EntityManager entityManager;
        private final AtomicBoolean failOnNextSave = new AtomicBoolean();

        FailingPaymentRecordRepository(PaymentRecordRepository delegate, EntityManager entityManager) {
            this.delegate = delegate;
            this.entityManager = entityManager;
        }

        public void failOnNextSave() {
            failOnNextSave.set(true);
        }

        public void disarm() {
            failOnNextSave.set(false);
        }

        @Override
        public PaymentRecord save(PaymentRecord payment) {
            if (failOnNextSave.getAndSet(false)) {
                entityManager.flush();
                throw new IllegalStateException(INJECTED_FAILURE);
            }
            return delegate.save(payment);
        }

        @Override
        public Optional<PaymentRecord> findByOrderId(long orderId) {
            return delegate.findByOrderId(orderId);
        }

        @Override
        public List<PaymentRecord> findByOrderIds(Collection<Long> orderIds) {
            return delegate.findByOrderIds(orderIds);
        }

        @Override
        public Optional<PaymentRecord> findByProviderOrderId(String providerOrderId) {
            return delegate.findByProviderOrderId(providerOrderId);
        }

        @Override
        public Optional<PaymentRecord> findByAttemptKey(String attemptKey) {
            return delegate.findByAttemptKey(attemptKey);
        }

        @Override
        public List<Long> findExpirable(Instant startedAtOrBefore, int limit) {
            return delegate.findExpirable(startedAtOrBefore, limit);
        }
    }
}
