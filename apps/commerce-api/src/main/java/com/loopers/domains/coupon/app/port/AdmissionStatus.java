package com.loopers.domains.coupon.app.port;

import java.time.Instant;

// 확인 모드가 보고하는 설정 행 상태. 행이 없으면 닫힘·이유 없음·epoch 0이다.
public record AdmissionStatus(boolean open, String closedReason, Instant closedAt, long epoch) {
}
