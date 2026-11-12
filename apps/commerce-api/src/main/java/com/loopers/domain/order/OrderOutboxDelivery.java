package com.loopers.domain.order;

/** relay가 한 번 전송할 때 쓰는 저장값. 행의 eventId·key·payload를 그대로 담는다. */
public record OrderOutboxDelivery(String eventId, String messageKey, String payload) {}
