package com.loopers.domain.purchase;

/**
 * Kafka 기록의 주소(topic, partition, offset). 재전달된 기록은 주소가 같다 — conflict·계약 깨짐 기록은 이 주소에 unique를 걸어
 * 기록 뒤 ack 전에 죽어 다시 받아도 한 번만 남긴다(docs/week6/decisions.md D-22).
 */
public record RecordAddress(String topic, int partition, long offset) {

    @Override
    public String toString() {
        return topic + "-" + partition + "@" + offset;
    }
}
