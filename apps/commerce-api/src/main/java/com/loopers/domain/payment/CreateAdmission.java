package com.loopers.domain.payment;

/** 생성 사전 확인 결과. READY 는 지금 차단되지 않았다는 뜻일 뿐 이후 실제 POST 권한을 보장하지 않는다(2.8). */
public enum CreateAdmission {
    READY,
    BLOCKED
}
