package com.loopers.infrastructure.payment;

import com.loopers.application.payment.OrderConfirmedPublishException;
import com.loopers.application.payment.OrderConfirmedPublisher;
import com.loopers.domain.order.OrderConfirmedEvent;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 테스트 전용 가짜 발행기 (6주차 설계 D5). FakeOrderConfirmedPublisherConfig 를 @Import 한 테스트에서만 쓴다.
 * 발행된 이벤트와 발행 시점의 DB 트랜잭션 활성 여부를 기록하고, "send 직전" hook 으로 중단·실패를 주입한다 (과제 2절 누락 실험용).
 */
public class FakeOrderConfirmedPublisher implements OrderConfirmedPublisher {

    private final List<OrderConfirmedEvent> published = new CopyOnWriteArrayList<>();
    private final List<Boolean> transactionActiveAtPublish = new CopyOnWriteArrayList<>();
    private volatile Consumer<OrderConfirmedEvent> beforeSend = event -> { };
    private volatile OrderConfirmedPublisher delegate;

    /** send 직전에 실행할 hook. 예외를 던지면 그 이벤트는 발행 목록에 남지 않는다 (위임 발행기도 호출되지 않는다). */
    public void beforeSend(Consumer<OrderConfirmedEvent> hook) {
        this.beforeSend = hook;
    }

    /** hook 을 통과한 이벤트를 실제 발행기(예: KafkaOrderConfirmedPublisher)로 보낸다. 누락 실험의 "정상 발행" 사례용. */
    public void delegateTo(OrderConfirmedPublisher delegate) {
        this.delegate = delegate;
    }

    /** 이후 발행이 지정한 outcome 의 OrderConfirmedPublishException 으로 끝난다. */
    public void willFailWith(OrderConfirmedPublishException.Outcome outcome) {
        beforeSend(event -> {
            throw new OrderConfirmedPublishException(outcome, event.eventId(), event.orderId(), "테스트 주입", null);
        });
    }

    public List<OrderConfirmedEvent> published() {
        return List.copyOf(published);
    }

    /** 어떤 발행이든 DB 트랜잭션 안에서 호출된 적이 있으면 true. "commit 뒤 발행" 장면용. */
    public boolean publishedInsideTransaction() {
        return transactionActiveAtPublish.contains(Boolean.TRUE);
    }

    public int publishCallCount() {
        return transactionActiveAtPublish.size();
    }

    public void reset() {
        published.clear();
        transactionActiveAtPublish.clear();
        beforeSend = event -> { };
        delegate = null;
    }

    @Override
    public void publish(OrderConfirmedEvent event) {
        transactionActiveAtPublish.add(TransactionSynchronizationManager.isActualTransactionActive());
        beforeSend.accept(event);
        if (delegate != null) {
            delegate.publish(event);
        }
        published.add(event);
    }
}
