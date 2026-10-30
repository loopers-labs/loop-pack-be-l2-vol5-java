package com.loopers.confg.kafka;

import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.BackOff;

/**
 * 일시적 실패는 back-off대로 재시도하고, 소진되거나 {@link NonRetryableEventException}이면 실패 record를 건너뛰지 않고
 * commit하지 않은 채 컨테이너를 멈춘다. 재시작하면 committed offset부터, 즉 실패한 record부터 다시 받는다.
 */
public class RetryThenStopErrorHandler extends DefaultErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(RetryThenStopErrorHandler.class);

    private final CommonContainerStoppingErrorHandler containerStoppingErrorHandler = new CommonContainerStoppingErrorHandler();

    public RetryThenStopErrorHandler(BackOff backOff) {
        // 기본 recoverer는 로그만 남기고 record를 건너뛴다. 여기서는 기록만 하고, 건너뛰는 대신 handleRemaining에서 멈춘다.
        super((record, exception) -> log.error("소비 실패 record를 건너뛰지 않고 컨테이너를 멈춤: topic={}, partition={}, offset={}",
            record.topic(), record.partition(), record.offset(), exception), backOff);
        addNotRetryableExceptions(NonRetryableEventException.class);
        // MANUAL_IMMEDIATE에서 recoverer가 끝난 record를 commit하지 않게 둘 다 끈다. 끄지 않으면 정상 처리로 보고 n+1을 commit한다.
        setCommitRecovered(false);
        setAckAfterHandle(false);
    }

    @Override
    public void handleRemaining(
        Exception thrownException,
        List<ConsumerRecord<?, ?>> records,
        Consumer<?, ?> consumer,
        MessageListenerContainer container
    ) {
        // 재시도가 남았으면 seek 후 RecordInRetryException을 던져 여기서 끝나고, 다음 poll에서 같은 record를 다시 받는다.
        super.handleRemaining(thrownException, records, consumer, container);
        containerStoppingErrorHandler.handleRemaining(thrownException, records, consumer, container);
    }
}
