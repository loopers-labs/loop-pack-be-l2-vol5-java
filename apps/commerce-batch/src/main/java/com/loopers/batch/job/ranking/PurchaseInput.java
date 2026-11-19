package com.loopers.batch.job.ranking;

import java.time.LocalDateTime;

/** 주간 입력 한 행. 입력의 업무 단위는 (orderId, productId) 다 (9주차 D-176). 시각은 UTC 벽시계 값 */
public record PurchaseInput(long orderId, long productId, int quantity, LocalDateTime confirmedAtUtc) {}
