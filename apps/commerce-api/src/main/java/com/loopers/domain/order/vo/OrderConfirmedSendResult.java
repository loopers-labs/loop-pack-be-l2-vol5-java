package com.loopers.domain.order.vo;

/** 전송 결과의 분류. 어떤 오류가 영구인지는 broker 를 아는 adapter 가 정함 (7주차 D-106) */
public sealed interface OrderConfirmedSendResult {

    /** broker 가 acks=all 로 확인함 */
    record Acked(String topic, int partition, long offset) implements OrderConfirmedSendResult {}

    /** 다시 보내면 성공할 수 있음. 로컬 대기 timeout 처럼 수신 여부를 모르는 경우도 포함 */
    record Transient(String error) implements OrderConfirmedSendResult {}

    /** 다시 보내도 성공하지 않음 */
    record Permanent(String error) implements OrderConfirmedSendResult {}
}
