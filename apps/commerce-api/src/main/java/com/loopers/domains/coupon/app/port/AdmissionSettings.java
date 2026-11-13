package com.loopers.domains.coupon.app.port;

// epoch는 수용 자리 키의 세대다(D138). 한 요청은 여기서 읽은 epoch로 획득·반환한다.
public record AdmissionSettings(int capacity, boolean open, long epoch) {
}
