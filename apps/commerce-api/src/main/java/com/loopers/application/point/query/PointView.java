package com.loopers.application.point.query;

/** 포인트 조회 전용 DTO 묶음. 조회 하나당 중첩 record 하나. 엔티티를 담지 않는다 (DR-31). */
public final class PointView {
    private PointView() {
    }

    /** FR-POINT-02 내 잔액. 설계 4-3-0 PointBalance. */
    public record Balance(Long userId, Long balance) {
    }
}
