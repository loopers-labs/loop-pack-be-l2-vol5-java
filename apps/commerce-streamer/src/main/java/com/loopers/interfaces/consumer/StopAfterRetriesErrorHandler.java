package com.loopers.interfaces.consumer;

import com.loopers.support.error.RejectedEventException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.BackOffHandler;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.TopicPartitionOffset;
import org.springframework.lang.NonNull;
import org.springframework.util.backoff.BackOff;

import java.util.List;

/**
 * 영구 오류는 재시도 없이 permanentRecoverer(DLT)로 보내 offset 을 진행하고 (6주차 D-86),
 * 그 밖의 오류는 backoff 로 재시도하다 소진되면 commit 없이 컨테이너를 멈춤 (D-87).
 * Spring Kafka 에 "소진 뒤 중단"이 없어 조립함. 소진 recoverer 가 예외를 던져 실패 record 로 seek 된 뒤 멈추므로 재시작하면 같은 record 부터 읽음
 */
@Slf4j
class StopAfterRetriesErrorHandler implements CommonErrorHandler {

    private final DefaultErrorHandler retrying;
    private final CommonContainerStoppingErrorHandler stopping = new CommonContainerStoppingErrorHandler();
    // 소진 recoverer 와 handleRemaining 은 같은 consumer 스레드에서 이어서 실행됨
    private final ThreadLocal<Boolean> exhausted = ThreadLocal.withInitial(() -> false);

    StopAfterRetriesErrorHandler(ConsumerRecordRecoverer permanentRecoverer, BackOff backOff, BackOffHandler backOffHandler) {
        this.retrying = new DefaultErrorHandler((record, exception) -> {
            if (isPermanent(exception)) {
                log.error("영구 오류라 DLT 로 옮김. 업무 완료가 아니라 확인할 대상임. topic={} partition={} offset={}",
                    record.topic(), record.partition(), record.offset(), exception);
                permanentRecoverer.accept(record, exception);
                return;
            }
            exhausted.set(true);
            throw new KafkaException("재시도 소진", exception);
        }, backOff, backOffHandler);
        this.retrying.addNotRetryableExceptions(RejectedEventException.class);
        // seek 경로에서는 컨테이너가 복구 뒤 commit 하지 않음(ackAfterHandle 은 쓰이지 않음). DLT 로 옮긴 record 만 n+1 을 commit 함.
        // 소진 recoverer 는 예외를 던져 복구로 끝나지 않으므로 commit 되지 않음
        this.retrying.setCommitRecovered(true);
        this.retrying.setRetryListeners((record, exception, attempt) ->
            log.warn("소비 실패, 재시도 대기. attempt={} topic={} partition={} offset={} cause={}",
                attempt, record.topic(), record.partition(), record.offset(), rootMessage(exception)));
    }

    @Override
    public void handleRemaining(@NonNull Exception thrownException, @NonNull List<ConsumerRecord<?, ?>> records, @NonNull Consumer<?, ?> consumer,
                                @NonNull MessageListenerContainer container) {
        try {
            retrying.handleRemaining(thrownException, records, consumer, container);
        } catch (RuntimeException e) {
            // 복구하지 못한 record 로 seek 하면 RecordInRetryException 을 던짐. 소진이면 그 대신 중단으로 넘김
            if (!exhausted.get()) {
                throw e;
            }
        }
        if (exhausted.get()) {
            exhausted.remove();
            ConsumerRecord<?, ?> failed = records.get(0);
            log.error("재시도 상한을 넘겨 소비를 중단함. 원인을 고친 뒤 재시작하면 같은 record 부터 다시 처리함. topic={} partition={} offset={}",
                failed.topic(), failed.partition(), failed.offset(), thrownException);
            stopping.handleRemaining(thrownException, records, consumer, container);
        }
    }

    /** record 정보가 없는 오류는 어느 record 를 건너뛸지 알 수 없어 멈춤 */
    @Override
    public void handleOtherException(@NonNull Exception thrownException, @NonNull Consumer<?, ?> consumer, @NonNull MessageListenerContainer container,
                                     boolean batchListener) {
        stopping.handleOtherException(thrownException, consumer, container, batchListener);
    }

    @Override
    public boolean seeksAfterHandling() {
        return retrying.seeksAfterHandling();
    }

    @Override
    public boolean deliveryAttemptHeader() {
        return retrying.deliveryAttemptHeader();
    }

    @Override
    public int deliveryAttempt(@NonNull TopicPartitionOffset topicPartitionOffset) {
        return retrying.deliveryAttempt(topicPartitionOffset);
    }

    @Override
    public void clearThreadState() {
        exhausted.remove();
        retrying.clearThreadState();
    }

    @Override
    public boolean isAckAfterHandle() {
        return retrying.isAckAfterHandle();
    }

    @Override
    public void setAckAfterHandle(boolean ack) {
        retrying.setAckAfterHandle(ack);
    }

    private static boolean isPermanent(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof RejectedEventException) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable exception) {
        Throwable root = exception;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
