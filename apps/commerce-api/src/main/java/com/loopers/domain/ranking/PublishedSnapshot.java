package com.loopers.domain.ranking;

import java.time.LocalDateTime;

/** 공개 pointer 가 가리키는 COMPLETED 회차. frozenAt 은 UTC 벽시계. */
public record PublishedSnapshot(String snapshotId, int revision, LocalDateTime frozenAt) {}
