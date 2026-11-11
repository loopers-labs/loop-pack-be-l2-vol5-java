package com.loopers.application.order;

/**
 * Outbox 한 행의 send 결과 (7주차 설계 D25·D27·D29). relay 는 이 값만 보고 결과 TX 를 결정한다.
 * - PUBLISHED: producer future 가 metadata 로 끝났다 (broker 확인). topic·partition·offset 이 채워진다
 * - RETRY: 전송 실패 확정 또는 대기 상한 안에 결과를 받지 못함(미확인). PENDING 유지·재시도 예약 대상.
 *   미확인은 "수신 0회" 가 아니다. record 가 뒤에서 발행될 수 있으므로 같은 eventId 재발행을 받아들인다
 * - BLOCKED: 다시 보내도 같은 결과인 영구 오류 (RecordTooLarge·Serialization·InvalidTopic·Authorization 계열)
 * detail 은 예외 클래스·요약 메시지만 담고 payload·접속 정보는 담지 않는다 (D33).
 */
public record OutboxSendResult(Outcome outcome, String detail, String topic, Integer partition, Long offset) {

    public enum Outcome {
        PUBLISHED, RETRY, BLOCKED
    }

    public static OutboxSendResult published(String topic, int partition, long offset) {
        return new OutboxSendResult(Outcome.PUBLISHED, null, topic, partition, offset);
    }

    public static OutboxSendResult retry(String detail) {
        return new OutboxSendResult(Outcome.RETRY, detail, null, null, null);
    }

    public static OutboxSendResult blocked(String detail) {
        return new OutboxSendResult(Outcome.BLOCKED, detail, null, null, null);
    }

    public boolean isPublished() {
        return outcome == Outcome.PUBLISHED;
    }
}
