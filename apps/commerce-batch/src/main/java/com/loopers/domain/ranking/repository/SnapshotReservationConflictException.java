package com.loopers.domain.ranking.repository;

public class SnapshotReservationConflictException extends RuntimeException {
    public SnapshotReservationConflictException(Throwable cause) {
        super("입력 snapshot 예약이 다른 실행과 충돌했습니다.", cause);
    }
}
