package com.loopers.domain.ranking;

import java.time.ZonedDateTime;

/** 고정 입력의 한 행. checksum 직렬화의 단위다. */
public record SnapshotInputLine(Long orderId, Long productId, int quantity, ZonedDateTime confirmedAt) {
}
