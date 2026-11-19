package com.loopers.domain.ranking;

import java.time.Instant;

/**
 * 기간의 공개본인 회차. 그 기간에서 완성된 회차 중 번호가 가장 크다. calculatedAt 뒤에 도착한 구매는 이 결과에 없다.
 */
public record PublishedRevision(int revision, Instant calculatedAt) {
}
