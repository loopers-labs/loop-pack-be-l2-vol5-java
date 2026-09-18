package com.loopers.interfaces.api;

/**
 * {@code X-USER-ID}로 식별한 고객 요청자다.
 */
public record Requester(Long userId) {
}
