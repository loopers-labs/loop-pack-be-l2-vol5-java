package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.EventSource;
import com.loopers.application.purchase.PurchaseActivityHandler;
import com.loopers.application.purchase.port.OrderEventAnomalyRepository.Kind;
import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.interfaces.consumer.OrderEventParseResult.Invalid;
import com.loopers.interfaces.consumer.OrderEventParseResult.Parsed;
import com.loopers.interfaces.consumer.OrderEventParseResult.Reason;
import com.loopers.interfaces.consumer.OrderEventParseResult.Unsupported;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class OrderEventsListener {

    public static final String LISTENER_ID = "purchase-activity-order-events";

    private final OrderConfirmedEventParser orderConfirmedEventParser;
    private final PurchaseActivityHandler purchaseActivityHandler;

    public OrderEventsListener(
        OrderConfirmedEventParser orderConfirmedEventParser,
        PurchaseActivityHandler purchaseActivityHandler
    ) {
        this.orderConfirmedEventParser = orderConfirmedEventParser;
        this.purchaseActivityHandler = purchaseActivityHandler;
    }

    // ack는 handler 트랜잭션 commit이 끝난 뒤에만 한다. 예외가 나면 ack 없이 error handler로 넘어간다.
    // committed offset이 없을 때 latest면 그 전 이벤트가 영구 누락되므로 이 group만 earliest로 둔다. 재처리는 멱등 소비가 흡수한다.
    @KafkaListener(
        id = LISTENER_ID,
        topics = "${commerce.kafka.topics.order-events}",
        groupId = "purchase-activity",
        containerFactory = KafkaConfig.RECORD_LISTENER,
        properties = "auto.offset.reset=earliest"
    )
    public void onOrderEvent(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        EventSource source = new EventSource(record.topic(), record.partition(), record.offset(), payloadText(record.value()));
        OrderEventParseResult result = orderConfirmedEventParser.parse(record.key(), record.value());
        switch (result) {
            case Parsed parsed -> purchaseActivityHandler.consume(parsed.event(), source);
            case Invalid invalid -> purchaseActivityHandler.recordInvalid(
                kindOf(invalid.reason()), invalid.eventId(), invalid.orderId(), source, invalid.reason() + ": " + invalid.detail());
            case Unsupported unsupported -> throw new UnsupportedOrderEventException(
                unsupported.detail() + " (topic=" + source.topic() + ", partition=" + source.partition()
                    + ", offset=" + source.offset() + ")");
        }
        acknowledgment.acknowledge();
    }

    private Kind kindOf(Reason reason) {
        if (reason == Reason.KEY_MISMATCH) {
            return Kind.KEY_MISMATCH;
        }
        return Kind.INVALID_PAYLOAD;
    }

    private String payloadText(byte[] value) {
        if (value == null) {
            return null;
        }
        return new String(value, StandardCharsets.UTF_8);
    }
}
