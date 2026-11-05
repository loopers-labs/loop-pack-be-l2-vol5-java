package com.loopers.support.kafka;

import com.loopers.domains.order.app.port.OrderConfirmedMessageSender;
import com.loopers.domains.order.app.port.OrderConfirmedOutbox;
import com.loopers.domains.order.app.port.OrderConfirmedPayload;
import com.loopers.domains.order.app.port.OutboxMessage;
import com.loopers.domains.order.app.port.SendOutcome;
import com.loopers.domains.order.out.KafkaOrderConfirmedMessageSender;
import com.loopers.domains.order.out.OrderConfirmedOutboxPersistenceAdapter;
import com.loopers.domains.payment.app.port.PaymentRecordRepository;
import com.loopers.domains.payment.domain.PaymentRecord;
import com.loopers.domains.payment.out.PaymentRecordPersistenceAdapter;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.TimeoutException;
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

    @Bean
    @Primary
    ScriptedMessageSender scriptedMessageSender(KafkaOrderConfirmedMessageSender kafkaOrderConfirmedMessageSender) {
        return new ScriptedMessageSender(kafkaOrderConfirmedMessageSender);
    }

    @Bean
    @Primary
    FailingOrderConfirmedOutbox failingOrderConfirmedOutbox(OrderConfirmedOutboxPersistenceAdapter delegate,
        EntityManager entityManager) {
        return new FailingOrderConfirmedOutbox(delegate, entityManager);
    }

    // 실제 producer factory로 보내다가, 무장하면 send를 실패시킨다. 주입 예외는 broker 수준(Kafka TimeoutException)이다.
    // 무장하지 않은 send는 producer가 받은 실제 broker 확인(partition·offset)을 모은다. 운영 전송 결과에는 metadata가 없어 여기서 본다.
    public static class FailingKafkaTemplate extends KafkaTemplate<String, String> {
        public static final String INJECTED_FAILURE = "주입된 Kafka send 실패";

        public enum Failure { NONE, SYNC_THROW, ASYNC_FAILED }

        private final AtomicReference<Failure> failure = new AtomicReference<Failure>(Failure.NONE);
        private final List<RecordMetadata> acked = new CopyOnWriteArrayList<RecordMetadata>();

        FailingKafkaTemplate(KafkaTemplate<String, String> delegate) {
            super(delegate.getProducerFactory());
        }

        public void fail(Failure next) {
            failure.set(next);
        }

        public List<RecordMetadata> acked() {
            return List.copyOf(acked);
        }

        public void resetAcked() {
            acked.clear();
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(String topic, String key, String data) {
            return switch (failure.get()) {
                case SYNC_THROW -> throw new TimeoutException(INJECTED_FAILURE);
                case ASYNC_FAILED -> CompletableFuture.failedFuture(new TimeoutException(INJECTED_FAILURE));
                case NONE -> super.send(topic, key, data).thenApply(result -> {
                    acked.add(result.getRecordMetadata());
                    return result;
                });
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

    // 무장하면 다음 append 한 번을 실패시킨다. 앞선 주문 변경은 DB로 보낸 뒤 실패해 rollback이 실제 SQL을 되돌리게 한다.
    // FAIL_AFTER_INSERT는 실제 어댑터로 insert까지 한 뒤 같은 트랜잭션에서 실패한다. 어댑터의 트랜잭션 경계가 바뀌면 행이 남아 드러난다.
    public static class FailingOrderConfirmedOutbox implements OrderConfirmedOutbox {
        public static final String INJECTED_FAILURE = "주입된 Outbox 저장 실패";
        public static final String INJECTED_SERIALIZE_FAILURE = "주입된 주문 확정 메시지 직렬화 실패";
        public static final String INJECTED_MARK_PUBLISHED_FAILURE = "주입된 PUBLISHED 기록 전 중단";

        public enum Failure { NONE, FAIL_BEFORE_INSERT, FAIL_AFTER_INSERT, FAIL_SERIALIZE }

        private final OrderConfirmedOutbox delegate;
        private final EntityManager entityManager;
        private final AtomicReference<Failure> failure = new AtomicReference<Failure>(Failure.NONE);
        private final List<Integer> findDueLimits = new CopyOnWriteArrayList<Integer>();
        private final AtomicBoolean failOnNextMarkPublished = new AtomicBoolean();
        private final AtomicBoolean skipAppends = new AtomicBoolean();

        FailingOrderConfirmedOutbox(OrderConfirmedOutbox delegate, EntityManager entityManager) {
            this.delegate = delegate;
            this.entityManager = entityManager;
        }

        public void failOnNextAppend(Failure next) {
            failure.set(next);
        }

        // broker 확인 뒤 PUBLISHED 기록 전에 예외로 회차를 끊어 재현한다. 실제 어댑터를 부르지 않으므로 행은 PENDING으로 남는다.
        public void failOnNextMarkPublished() {
            failOnNextMarkPublished.set(true);
        }

        // 켜 두는 동안 append를 아무것도 하지 않게 해 "확정은 commit됐는데 Outbox 기록이 없는" 6주차와 같은 상태를 만든다.
        // 실제 6주차 실행물이 아니라 Outbox 기록을 비운 대역이다. disarm()으로 끈다.
        public void skipAppends() {
            skipAppends.set(true);
        }

        public void disarm() {
            failure.set(Failure.NONE);
            failOnNextMarkPublished.set(false);
            skipAppends.set(false);
            findDueLimits.clear();
        }

        // relay가 회차마다 묶음을 몇 개, 어떤 크기로 조회했는지 본다.
        public List<Integer> findDueLimits() {
            return List.copyOf(findDueLimits);
        }

        @Override
        public void append(OrderConfirmedPayload payload) {
            if (skipAppends.get()) {
                return;
            }
            switch (failure.getAndSet(Failure.NONE)) {
                case FAIL_BEFORE_INSERT -> {
                    entityManager.flush();
                    throw new IllegalStateException(INJECTED_FAILURE);
                }
                case FAIL_AFTER_INSERT -> {
                    delegate.append(payload);
                    entityManager.flush();
                    throw new IllegalStateException(INJECTED_FAILURE);
                }
                case FAIL_SERIALIZE -> {
                    entityManager.flush();
                    throw new IllegalStateException(INJECTED_SERIALIZE_FAILURE);
                }
                case NONE -> delegate.append(payload);
            }
        }

        @Override
        public List<OutboxMessage> findDue(Instant now, int limit) {
            findDueLimits.add(limit);
            return delegate.findDue(now, limit);
        }

        @Override
        public int markPublished(long id, Instant publishedAt) {
            if (failOnNextMarkPublished.getAndSet(false)) {
                throw new IllegalStateException(INJECTED_MARK_PUBLISHED_FAILURE);
            }
            return delegate.markPublished(id, publishedAt);
        }

        @Override
        public int markBrokerFailure(long id, Instant nextAttemptAt, String error) {
            return delegate.markBrokerFailure(id, nextAttemptAt, error);
        }

        @Override
        public int markRowFailure(long id, Instant nextAttemptAt, String error, boolean blocked) {
            return delegate.markRowFailure(id, nextAttemptAt, error, blocked);
        }

        @Override
        public int resumeBlocked(String eventId, Instant now) {
            return delegate.resumeBlocked(eventId, now);
        }

        @Override
        public StoredComparison compare(OrderConfirmedPayload payload) {
            return delegate.compare(payload);
        }
    }

    // relay가 쓰는 전송 포트 대역. key별로 결과를 지정하지 않으면 실제 Kafka sender로 보낸다.
    // 보낸 순서를 기록하고, 보낼 때마다 실행할 동작(시계 앞당기기·막기)을 걸 수 있다. 운영 sender의 분류 규칙은 거치지 않는다.
    public static class ScriptedMessageSender implements OrderConfirmedMessageSender {
        private final OrderConfirmedMessageSender delegate;
        private final Map<String, Deque<SendOutcome>> scripted = new ConcurrentHashMap<String, Deque<SendOutcome>>();
        private final Map<String, SendOutcome> always = new ConcurrentHashMap<String, SendOutcome>();
        private final List<String> sentKeys = new CopyOnWriteArrayList<String>();
        private final AtomicReference<Runnable> onSend = new AtomicReference<Runnable>(() -> { });
        private final AtomicBoolean publishWithoutBroker = new AtomicBoolean();

        ScriptedMessageSender(OrderConfirmedMessageSender delegate) {
            this.delegate = delegate;
        }

        public void script(String key, SendOutcome... outcomes) {
            scripted.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<SendOutcome>()).addAll(List.of(outcomes));
        }

        public void always(String key, SendOutcome outcome) {
            always.put(key, outcome);
        }

        // 지정하지 않은 key도 broker에 보내지 않고 성공으로 돌려준다(순서·건수만 보는 장면).
        public void publishWithoutBroker() {
            publishWithoutBroker.set(true);
        }

        public void onSend(Runnable action) {
            onSend.set(action);
        }

        public List<String> sentKeys() {
            return List.copyOf(sentKeys);
        }

        public long sendCount(String key) {
            return sentKeys.stream().filter(key::equals).count();
        }

        public void reset() {
            scripted.clear();
            always.clear();
            sentKeys.clear();
            onSend.set(() -> { });
            publishWithoutBroker.set(false);
        }

        @Override
        public SendOutcome send(String key, String payload) {
            sentKeys.add(key);
            onSend.get().run();
            Deque<SendOutcome> queued = scripted.get(key);
            if (queued != null && !queued.isEmpty()) {
                return queued.poll();
            }
            if (always.containsKey(key)) {
                return always.get(key);
            }
            if (publishWithoutBroker.get()) {
                return new SendOutcome.Published();
            }
            return delegate.send(key, payload);
        }
    }
}
