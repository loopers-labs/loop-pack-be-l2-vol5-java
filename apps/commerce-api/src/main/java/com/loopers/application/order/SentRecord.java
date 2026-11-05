package com.loopers.application.order;

/**
 * broker가 받은 위치(docs/week7/decisions.md D-9). relay가 PUBLISHED를 기록할 때 함께 남긴다(D-11) — 재발행이 생기면
 * 같은 eventId가 어느 offset들에 실렸는지 추적하는 근거다.
 */
public record SentRecord(String topic, int partition, long offset) {}
