package com.loopers.interfaces.api.point;

public class PointAdminV1Dto {
    /** EP-27/28 요청 (DR-20: 대상 사용자는 바디 userId). userId 누락·타입 오류는 ER-22, amount 는 ER-09. */
    public record AdjustRequest(Long userId, Long amount) {
    }
}
