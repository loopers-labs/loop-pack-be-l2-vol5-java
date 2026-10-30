package com.loopers.config.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.TopicPartitionOffset;
import org.springframework.util.backoff.BackOff;

import java.util.List;

/**
 * 실패한 record 를 건너뛰지 않는 error handler. DefaultErrorHandler 로 backOff 만큼 재시도하고, 소진되면(재시도 불가 예외는 바로) 컨테이너를 멈춘다.
 * DefaultErrorHandler 의 기본 recoverer 는 로그만 남기고 record 를 건너뛰어 offset 이 진행된다. 여기서는 recoverer 가 "소진"만 표시하고 예외를 던져
 * record 를 다시 seek 하게 두고(복구하지 않음 → commit 없음), CommonContainerStoppingErrorHandler 로 멈춘다.
 * 재시작하면 committed offset(실패 record)부터 다시 읽는다. 원인 제거 후 운영자가 컨테이너를 재시작한다.
 */
@Slf4j
public class RetryThenStopErrorHandler implements CommonErrorHandler {
    private final DefaultErrorHandler retrying;
    private final CommonContainerStoppingErrorHandler stopping = new CommonContainerStoppingErrorHandler();
    private final ThreadLocal<Exception> exhausted = new ThreadLocal<>();

    @SafeVarargs
    public RetryThenStopErrorHandler(BackOff backOff, Class<? extends Exception>... notRetryable) {
        this.retrying = new DefaultErrorHandler((record, e) -> {
            exhausted.set(e);
            throw new IllegalStateException("재시도 소진, record 를 건너뛰지 않는다", e);
        }, backOff);
        this.retrying.addNotRetryableExceptions(notRetryable);
        this.retrying.setCommitRecovered(false);
        this.retrying.setAckAfterHandle(false);
    }

    @Override
    public boolean seeksAfterHandling() {
        return true;
    }

    @Override
    public boolean isAckAfterHandle() {
        return false;
    }

    @Override
    public void setAckAfterHandle(boolean ack) {
        // 처리하지 못한 record 를 ack 하지 않는다.
    }

    @Override
    public void handleRemaining(Exception thrownException, List<ConsumerRecord<?, ?>> records, Consumer<?, ?> consumer,
                                MessageListenerContainer container) {
        try {
            retrying.handleRemaining(thrownException, records, consumer, container);
        } catch (RuntimeException retryOrRecoveryFailed) { // 재시도 중·복구 실패 모두 record 를 다시 seek 하고 예외로 끝난다 (RecordInRetryException)
            Exception cause = exhausted.get();
            if (cause == null) {
                throw retryOrRecoveryFailed;
            }
            exhausted.remove();
            ConsumerRecord<?, ?> record = records.get(0);
            log.error("record 처리 실패, 컨테이너를 멈춘다. offset 은 진행하지 않는다 [listener = {}, topic = {}, partition = {}, offset = {}, key = {}]",
                container.getListenerId(), record.topic(), record.partition(), record.offset(), record.key(), cause);
            stopping.handleRemaining(cause, records, consumer, container);
        }
    }

    @Override
    public void handleOtherException(Exception thrownException, Consumer<?, ?> consumer, MessageListenerContainer container, boolean batchListener) {
        retrying.handleOtherException(thrownException, consumer, container, batchListener);
    }

    @Override
    public int deliveryAttempt(TopicPartitionOffset topicPartitionOffset) {
        return retrying.deliveryAttempt(topicPartitionOffset);
    }

    @Override
    public void clearThreadState() {
        exhausted.remove();
        retrying.clearThreadState();
    }
}
