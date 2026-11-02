package com.loopers.outbox.application.port.out;

import java.util.concurrent.CompletableFuture;

public interface MessageSendPort {

    /**
     * 비동기로 보낸다 (D-4). broker가 기록을 확인하면 그 위치로, 실패하면 예외로 완료된다.
     */
    CompletableFuture<SentPosition> send(String topic, String key, String payload);

    record SentPosition(int partition, long offset) {}
}
