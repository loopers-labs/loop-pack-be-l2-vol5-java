package com.loopers.application.purchase;

public record EventSource(String topic, int partition, long offset, String payload) {
}
