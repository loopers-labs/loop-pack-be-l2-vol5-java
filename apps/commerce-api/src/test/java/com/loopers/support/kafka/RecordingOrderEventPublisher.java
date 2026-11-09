package com.loopers.support.kafka;

import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderEventPublication;
import com.loopers.domain.order.OrderEventPublisher;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 통합 테스트에서 broker 대신 발행 호출을 기록하는 발행기. 결제 시나리오 테스트가 Kafka broker 없이도 "어떤 경우에 발행하는가"를 관찰한다.
 * 실제 broker로 보내는 동작은 {@code KafkaOrderEventPublisherTest}가 따로 확인한다.
 *
 * <p>{@link #failWith}로 발행 실패를 주입할 수 있다. 실패한 시도는 {@link #attempts()}에만 남고 {@link #events()}에는 없다(broker record가 없는 상태).
 * {@link #beforeSend}는 commit 뒤·send 직전에 호출되는 관찰 지점이고, {@link #delegateTo}를 주면 기록하는 대신 실제 발행기로 보낸다
 * (누락 구간 재현 테스트가 실제 broker로 보낼 때 쓴다).
 */
@Primary
@Component
public class RecordingOrderEventPublisher implements OrderEventPublisher {

    private final List<OrderConfirmedEvent> attempts = new CopyOnWriteArrayList<>();
    private final List<OrderConfirmedEvent> events = new CopyOnWriteArrayList<>();
    private volatile String failureReason;
    private volatile OrderEventPublisher delegate;
    private volatile Consumer<OrderConfirmedEvent> beforeSend;

    @Override
    public OrderEventPublication publish(OrderConfirmedEvent event) {
        attempts.add(event);
        Consumer<OrderConfirmedEvent> hook = beforeSend;
        if (hook != null) {
            hook.accept(event);
        }
        String reason = failureReason;
        if (reason != null) {
            return new OrderEventPublication.Failed(reason);
        }
        OrderEventPublisher real = delegate;
        if (real != null) {
            OrderEventPublication publication = real.publish(event);
            if (publication instanceof OrderEventPublication.Sent) {
                events.add(event);
            }
            return publication;
        }
        events.add(event);
        return new OrderEventPublication.Sent("recording", 0, events.size() - 1L);
    }

    /** 이후의 발행을 이 이유로 실패시킨다. null이면 다시 정상으로 돌아간다. */
    public void failWith(String reason) {
        this.failureReason = reason;
    }

    /** 이후의 발행을 기록하는 대신 이 발행기(예: 실제 broker로 보내는 발행기)로 보낸다. null이면 기록으로 돌아간다. */
    public void delegateTo(OrderEventPublisher real) {
        this.delegate = real;
    }

    /** 발행 호출 직전(주문 commit 뒤)에 이벤트를 관찰한다. null이면 해제한다. */
    public void beforeSend(Consumer<OrderConfirmedEvent> observer) {
        this.beforeSend = observer;
    }

    /** 발행 시도 전부 (실패 포함). */
    public List<OrderConfirmedEvent> attempts() {
        return List.copyOf(attempts);
    }

    /** broker가 받은 것으로 기록한 이벤트. */
    public List<OrderConfirmedEvent> events() {
        return List.copyOf(events);
    }

    public void clear() {
        attempts.clear();
        events.clear();
        failureReason = null;
        delegate = null;
        beforeSend = null;
    }
}
