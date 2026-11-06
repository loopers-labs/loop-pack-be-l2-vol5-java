package com.loopers.support.outbox;

import com.loopers.domain.order.OrderEventPublication;
import com.loopers.domain.order.OrderOutboxMessage;
import com.loopers.domain.order.OrderOutboxSender;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 실제 발행기에 그대로 넘기고, broker가 돌려준 결과(성공이면 topic·partition·offset)를 남긴다.
 * 중단 실험에서 "broker 확인까지는 실제로 끝났다"를 send 성공 metadata로 확인하는 데 쓴다. 결과를 바꾸지 않는다.
 */
public final class RecordingOutboxSender implements OrderOutboxSender {

    private final OrderOutboxSender delegate;
    private final List<OrderEventPublication> results = new CopyOnWriteArrayList<>();

    private RecordingOutboxSender(OrderOutboxSender delegate) {
        this.delegate = delegate;
    }

    public static RecordingOutboxSender of(OrderOutboxSender delegate) {
        return new RecordingOutboxSender(delegate);
    }

    @Override
    public OrderEventPublication send(OrderOutboxMessage message) {
        OrderEventPublication result = delegate.send(message);
        results.add(result);
        return result;
    }

    /** 보낸 순서대로의 결과. */
    public List<OrderEventPublication> results() {
        return List.copyOf(results);
    }
}
