package com.loopers.application.purchase;

import com.loopers.domain.purchase.PermanentEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * 영구 오류(설계 D12) record 를 DLT 로 보낸다. 원본 key·value bytes 를 그대로 보내고 원인·원본 위치를 헤더에 담는다.
 * 구현은 broker 저장 확인까지 기다리고, 실패하면 예외를 던진다. 리스너는 그 실패를 일시 오류처럼 재시도하고 소진되면 중단한다.
 */
public interface DeadLetterPublisher {

    void publish(ConsumerRecord<String, byte[]> record, PermanentEventException cause);
}
