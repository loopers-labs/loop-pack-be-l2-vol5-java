package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.OrderConfirmationCommand;
import com.loopers.application.purchase.OrderConfirmationHandler;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** record 파싱·DB 처리를 수행한다. 업무용 BATCH listener는 한 인자 메서드의 정상 반환 뒤 진행한다. */
@Component
@RequiredArgsConstructor
public class OrderConfirmedRecordAdapter {
    private final OrderConfirmedMessageParser parser;
    private final OrderConfirmationHandler handler;

    public void handle(ConsumerRecord<String, byte[]> record) {
        handler.handle(parse(record));
    }

    /** 과거 MANUAL/IMMEDIATE 비교 실험의 호출 호환용이다. 업무용 BATCH listener는 사용하지 않는다. */
    public void handle(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        Objects.requireNonNull(acknowledgment, "acknowledgment must not be null");
        handle(record);
        acknowledgment.acknowledge();
    }

    private OrderConfirmationCommand parse(ConsumerRecord<String, byte[]> record) {
        try {
            return parser.parse(record.key(), record.value());
        } catch (IllegalArgumentException invalid) {
            throw new InvalidOrderConfirmedMessageException(invalid);
        }
    }
}
